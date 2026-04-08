import java.io.*;
import java.util.*;

class Database implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;
    private Map<String, Table> tables;
    private String dbPath;
    public static final String TABLES_DIR = "tables";
    private static final String METADATA_FILE = "metadata.dat";

    public Database(String name) throws Exception {
        this.name = name.toLowerCase();
        this.tables = new HashMap<>();
        
        // Create database directory
        this.dbPath = name.toLowerCase();
        File dbDir = new File(dbPath);
        
        if (!dbDir.exists()) {
            if (!dbDir.mkdirs()) {
                throw new Exception("Failed to create database directory: " + dbPath);
            }
            // Create tables subdirectory
            File tablesDir = new File(dbPath + File.separator + TABLES_DIR);
            if (!tablesDir.mkdirs()) {
                throw new Exception("Failed to create tables directory");
            }
        }
        
        // Load existing metadata if any
        loadMetadata();
    }

    private Database(String name, String dbPath) {
        this.name = name.toLowerCase();
        this.tables = new HashMap<>();
        this.dbPath = dbPath;
    }

    @SuppressWarnings("unchecked")
    private void loadMetadata() throws Exception {
        File metadataFile = new File(dbPath + File.separator + METADATA_FILE);
        if (!metadataFile.exists()) {
            return;
        }
        
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(metadataFile))) {
            Map<String, TableMetadata> metadataMap = (Map<String, TableMetadata>) ois.readObject();
            
            for (Map.Entry<String, TableMetadata> entry : metadataMap.entrySet()) {
                String tableName = entry.getKey();
                TableMetadata metadata = entry.getValue();
                Table table = Table.load(this, tableName, metadata);
                if (table != null) {
                    tables.put(tableName, table);
                }
            }
        } catch (IOException | ClassNotFoundException e) {
            throw new Exception("Error loading metadata: " + e.getMessage());
        }
    }

    private void saveMetadata() throws Exception {
        File metadataFile = new File(dbPath + File.separator + METADATA_FILE);
        Map<String, TableMetadata> metadataMap = new HashMap<>();
        
        for (Map.Entry<String, Table> entry : tables.entrySet()) {
            metadataMap.put(entry.getKey(), entry.getValue().getMetadata());
        }
        
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(metadataFile))) {
            oos.writeObject(metadataMap);
        } catch (IOException e) {
            throw new Exception("Error saving metadata: " + e.getMessage());
        }
    }

    public static Database load(String dbName) {
        String dbPath = dbName.toLowerCase();
        File dbDir = new File(dbPath);
        
        if (!dbDir.exists() || !dbDir.isDirectory()) {
            return null;
        }
        
        Database db = new Database(dbName, dbPath);
        try {
            db.loadMetadata();
            return db;
        } catch (Exception e) {
            System.err.println("Error loading database " + dbName + ": " + e.getMessage());
            return null;
        }
    }

    public void createTable(String tableName, List<String> attrTokens) throws Exception {
        if (tables.containsKey(tableName.toLowerCase())) {
            throw new Exception("Table " + tableName + " already exists");
        }

        Table table = new Table(this, tableName, attrTokens);
        tables.put(tableName.toLowerCase(), table);
        saveMetadata();
        System.out.println("Table " + tableName + " created");
    }

    public Table getTable(String tableName) throws Exception {
        Table table = tables.get(tableName.toLowerCase());
        if (table == null) {
            throw new Exception("Table " + tableName + " does not exist");
        }
        return table;
    }

    public void insertInto(String tableName, List<String> values) throws Exception {
        Table table = getTable(tableName);
        table.insert(values);
        saveMetadata();
    }

    public void select(String tableName, List<String> attributes, Condition whereCondition) throws Exception {
        Table table = getTable(tableName);
        table.select(attributes, whereCondition);
    }

    public void deleteFrom(String tableName, Condition whereCondition) throws Exception {
        Table table = getTable(tableName);
        int deletedCount = table.delete(whereCondition);
        System.out.println(deletedCount + " record(s) deleted");

        // If no WHERE clause and all records deleted, remove the table
        if (whereCondition == null) {
            // Delete table files
            table.close();
            File tableDir = new File(table.getTablePath());
            deleteDirectory(tableDir);
            tables.remove(tableName.toLowerCase());
            saveMetadata();
            System.out.println("Table " + tableName + " removed from database");
        } else {
            saveMetadata();
        }
    }

    private void deleteDirectory(File dir) {
        if (dir.exists() && dir.isDirectory()) {
            File[] files = dir.listFiles();
            if (files != null) {
                for (File file : files) {
                    file.delete();
                }
            }
            dir.delete();
        }
    }

    public void update(String tableName, Map<String, String> setClauses, Condition whereCondition) throws Exception {
        Table table = getTable(tableName);
        int updatedCount = table.update(setClauses, whereCondition);
        System.out.println(updatedCount + " record(s) updated");
        saveMetadata();
    }

    public void renameTable(String tableName, List<String> newAttrNames) throws Exception {
        Table table = getTable(tableName);
        table.renameAttributes(newAttrNames);
        saveMetadata();
        System.out.println("Table " + tableName + " attributes renamed");
    }

    public void createTableFromSelect(String newTableName, String keyAttr, LetSelectResult result) throws Exception {
        if (tables.containsKey(newTableName.toLowerCase())) {
            throw new Exception("Table " + newTableName + " already exists");
        }

        // Verify key attribute is in selected attributes
        boolean keyFound = false;
        for (String attrName : result.attributeNames) {
            if (attrName.equalsIgnoreCase(keyAttr)) {
                keyFound = true;
                break;
            }
        }

        if (!keyFound) {
            throw new Exception("Key attribute " + keyAttr + " not found in selected attributes");
        }

        // Build CREATE TABLE tokens
        List<String> createTokens = new ArrayList<>();
        for (String attrName : result.attributeNames) {
            createTokens.add(attrName);
            DataType type = result.attributeTypes.get(attrName);
            createTokens.add(type.toString());

            if (attrName.equalsIgnoreCase(keyAttr)) {
                createTokens.add("PRIMARY");
                createTokens.add("KEY");
            }

            createTokens.add(",");
        }
        createTokens.remove(createTokens.size() - 1); // Remove last comma

        // Create the new table
        Table newTable = new Table(this, newTableName, createTokens);

        // Insert all records from SELECT result
        for (Record record : result.records) {
            List<String> valueTokens = new ArrayList<>();
            for (String attrName : result.attributeNames) {
                Object value = record.getValue(attrName);
                if (value instanceof String) {
                    valueTokens.add("\"" + value + "\"");
                } else {
                    valueTokens.add(value.toString());
                }
            }
            newTable.insert(valueTokens);
        }

        tables.put(newTableName.toLowerCase(), newTable);
        saveMetadata();
        System.out.println("Table " + newTableName + " created from SELECT with key " + keyAttr);
    }

    public void describeAll() {
        if (tables.isEmpty()) {
            System.out.println("No tables in database");
            return;
        }

        for (Table table : tables.values()) {
            table.describe();
            System.out.println();
        }
    }

    public void describeTable(String tableName) throws Exception {
        Table table = getTable(tableName);
        table.describe();
    }

    public void close() throws Exception {
        saveMetadata();
        for (Table table : tables.values()) {
            table.close();
        }
    }

    public String getName() {
        return name;
    }

    public String getPath() {
        return dbPath;
    }
}

// Serializable metadata class for table persistence
class TableMetadata implements Serializable {
    private static final long serialVersionUID = 1L;
    
    private String name;
    private List<String> attributeNames;
    private List<DataType> attributeTypes;
    private List<Boolean> isPrimaryKey;
    private String primaryKey;
    private int recordCount;
    
    public TableMetadata(String name, List<Attribute> attributes, String primaryKey, int recordCount) {
        this.name = name;
        this.attributeNames = new ArrayList<>();
        this.attributeTypes = new ArrayList<>();
        this.isPrimaryKey = new ArrayList<>();
        
        for (Attribute attr : attributes) {
            this.attributeNames.add(attr.getName());
            this.attributeTypes.add(attr.getType());
            this.isPrimaryKey.add(attr.isPrimaryKey());
        }
        
        this.primaryKey = primaryKey;
        this.recordCount = recordCount;
    }
    
    public String getName() { return name; }
    public List<String> getAttributeNames() { return attributeNames; }
    public List<DataType> getAttributeTypes() { return attributeTypes; }
    public List<Boolean> getIsPrimaryKey() { return isPrimaryKey; }
    public String getPrimaryKey() { return primaryKey; }
    public int getRecordCount() { return recordCount; }
}
