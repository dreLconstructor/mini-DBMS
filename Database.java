import java.io.*;
import java.util.*;

class Database implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;
    private Map<String, Table> tables;

    public Database(String name) {
        this.name = name;
        this.tables = new HashMap<>();
    }

    public void createTable(String tableName, List<String> attrTokens) throws Exception {
        if (tables.containsKey(tableName.toLowerCase())) {
            throw new Exception("Table " + tableName + " already exists");
        }

        Table table = new Table(tableName, attrTokens);
        tables.put(tableName.toLowerCase(), table);
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
    }

    // [CHANGED] Now accepts List<String> tableNames to support multi-table cross-join.
    // Single-table queries delegate to Table.select() as before.
    public void select(List<String> tableNames, List<String> attributes, Condition whereCondition) throws Exception {
        if (tableNames.size() == 1) {
            // Single table — delegate to Table.select() unchanged
            Table table = getTable(tableNames.get(0));
            table.select(attributes, whereCondition);
        } else {
            // [CHANGED] Multi-table: perform a cross join across all named tables
            List<Table> tableList = new ArrayList<>();
            for (String tn : tableNames) {
                tableList.add(getTable(tn));
            }

            // Gather all attribute metadata and all records from each table
            // [CHANGED] Use getOrderedRecordPositions() so tables with a primary key
            // are traversed in BST in-order, matching the rubric requirement.
            List<List<Attribute>> allAttrLists = new ArrayList<>();
            List<List<Record>> allRecordLists = new ArrayList<>();
            for (Table t : tableList) {
                allAttrLists.add(t.getAttributes());
                List<Long> positions = t.getOrderedRecordPositions();
                List<Record> records = new ArrayList<>();
                for (Long pos : positions) {
                    Record r = t.readRecordAtPosition(pos);
                    if (r != null) records.add(r);
                }
                allRecordLists.add(records);
            }

            // Build combined attribute list (for WHERE evaluation and display)
            List<Attribute> combinedAttrs = new ArrayList<>();
            for (List<Attribute> attrList : allAttrLists) {
                combinedAttrs.addAll(attrList);
            }

            // Compute Cartesian product of all record lists
            List<Record> crossProduct = new ArrayList<>();
            crossProduct.add(new Record()); // seed with one empty record
            for (List<Record> rList : allRecordLists) {
                List<Record> newProduct = new ArrayList<>();
                for (Record existing : crossProduct) {
                    for (Record r : rList) {
                        // Merge existing combined record with next table's record
                        Record merged = new Record();
                        for (Map.Entry<String, Object> e : existing.getValues().entrySet()) {
                            merged.setValue(e.getKey(), e.getValue());
                        }
                        for (Map.Entry<String, Object> e : r.getValues().entrySet()) {
                            merged.setValue(e.getKey(), e.getValue());
                        }
                        newProduct.add(merged);
                    }
                }
                crossProduct = newProduct;
            }

            // Filter by WHERE condition
            List<Record> results = new ArrayList<>();
            for (Record r : crossProduct) {
                if (whereCondition == null || whereCondition.evaluate(r, combinedAttrs)) {
                    results.add(r);
                }
            }

            // Display results
            if (results.isEmpty()) {
                System.out.println("Nothing found");
                return;
            }

            // Determine which attributes to display
            List<String> displayAttrs;
            if (attributes.size() == 1 && attributes.get(0).equals("*")) {
                displayAttrs = new ArrayList<>();
                for (Attribute attr : combinedAttrs) {
                    displayAttrs.add(attr.getName());
                }
            } else {
                displayAttrs = attributes;
            }

            // Print header
            for (int i = 0; i < displayAttrs.size(); i++) {
                if (i > 0) System.out.print(" | ");
                System.out.print(displayAttrs.get(i).toUpperCase());
            }
            System.out.println();
            System.out.println("-".repeat(50));

            // Print rows
            int rowNum = 1;
            for (Record r : results) {
                System.out.print(rowNum + ". ");
                for (int i = 0; i < displayAttrs.size(); i++) {
                    if (i > 0) System.out.print(" | ");
                    Object val = r.getValue(displayAttrs.get(i));
                    if (val == null) System.out.print("null");
                    else if (val instanceof String) System.out.print("\"" + val + "\"");
                    else System.out.print(val);
                }
                System.out.println();
                rowNum++;
            }
        }
    }

    public void deleteFrom(String tableName, Condition whereCondition) throws Exception {
        Table table = getTable(tableName);
        int deletedCount = table.delete(whereCondition);
        System.out.println(deletedCount + " record(s) deleted");

        // If no WHERE clause, remove the table entirely
        if (whereCondition == null) {
            // [CHANGED] Close file handles before deleting physical files
            table.close();
            tables.remove(tableName.toLowerCase());
            // [CHANGED] Delete the .dat and .idx files from disk
            new File(tableName + ".dat").delete();
            new File(tableName + ".idx").delete();
            System.out.println("Table " + tableName + " removed from database");
        }
    }

    public void update(String tableName, Map<String, String> setClauses, Condition whereCondition) throws Exception {
        Table table = getTable(tableName);
        int updatedCount = table.update(setClauses, whereCondition);
        System.out.println(updatedCount + " record(s) updated");
    }

    public void renameTable(String tableName, List<String> newAttrNames) throws Exception {
        Table table = getTable(tableName);
        table.renameAttributes(newAttrNames);
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

        // [CHANGED] Build CREATE TABLE tokens with the key attribute FIRST.
        // parseAttributes() enforces that PRIMARY KEY must be on the first attribute,
        // so we must emit the key column before all others regardless of select-list order.
        List<String> orderedNames = new ArrayList<>();
        orderedNames.add(keyAttr);
        for (String attrName : result.attributeNames) {
            if (!attrName.equalsIgnoreCase(keyAttr)) orderedNames.add(attrName);
        }

        List<String> createTokens = new ArrayList<>();
        for (int idx = 0; idx < orderedNames.size(); idx++) {
            String attrName = orderedNames.get(idx);
            createTokens.add(attrName);
            DataType type = result.attributeTypes.get(attrName);
            createTokens.add(type.toString());
            if (idx == 0) { // key is always first
                createTokens.add("PRIMARY");
                createTokens.add("KEY");
            }
            if (idx < orderedNames.size() - 1) createTokens.add(",");
        }

        // Create the new table
        Table newTable = new Table(newTableName, createTokens);

        // [CHANGED] suppress "1 record inserted" spam during internal LET population
        newTable.setSilentInsert(true);

        // Insert all records from SELECT result; skip duplicates gracefully
        for (Record record : result.records) {
            List<String> valueTokens = new ArrayList<>();
            for (String attrName : orderedNames) {
                Object value = record.getValue(attrName);
                if (value instanceof String) {
                    valueTokens.add("\"" + value + "\"");
                } else {
                    valueTokens.add(value.toString());
                }
            }
            try {
                newTable.insert(valueTokens);
            } catch (Exception e) {
                // [CHANGED] Duplicate primary key during LET — skip silently like INSERT does
                System.out.println("Warning: skipping record in " + newTableName + " — " + e.getMessage());
            }
        }

        tables.put(newTableName.toLowerCase(), newTable);
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

    // [CHANGED] Deserialize a Database from <dbName>.db; returns null if file doesn't exist
    public static Database load(String dbName) {
        File file = new File(dbName + ".db");
        if (!file.exists()) {
            return null;
        }
        try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(file))) {
            return (Database) ois.readObject();
        } catch (Exception e) {
            System.err.println("Failed to load database '" + dbName + "': " + e.getMessage());
            return null;
        }
    }

    // [CHANGED] Serialize the entire Database to <name>.db so it persists across sessions
    public void close() throws Exception {
        for (Table table : tables.values()) {
            table.close();
        }
        // Persist the database object to disk
        try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(name + ".db"))) {
            oos.writeObject(this);
        }
    }

    public String getName() {
        return name;
    }
}
