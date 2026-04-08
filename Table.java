import java.io.*;
import java.util.*;

class Table implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;
    private List<Attribute> attributes;
    private String primaryKey;
    private transient RandomAccessFile dataFile;
    private transient BSTIndex index;
    private String tablePath;
    private String dataFileName;
    private String indexFileName;
    private int recordCount;
    private transient Database database;

    // Constructor for new table
    public Table(Database database, String name, List<String> attrTokens) throws Exception {
        this.database = database;
        this.name = name.toLowerCase();
        this.attributes = new ArrayList<>();
        this.recordCount = 0;
        parseAttributes(attrTokens);

        // Create table directory
        this.tablePath = database.getPath() + File.separator + Database.TABLES_DIR + File.separator + this.name;
        File tableDir = new File(tablePath);
        if (!tableDir.exists() && !tableDir.mkdirs()) {
            throw new Exception("Failed to create table directory");
        }

        // Create file names
        this.dataFileName = tablePath + File.separator + "data.dat";
        this.indexFileName = tablePath + File.separator + "index.idx";

        // Initialize files
        initFiles();
    }

    // Constructor for loading existing table
    private Table() {
    }

    // Load table from disk
    public static Table load(Database database, String tableName, TableMetadata metadata) throws Exception {
        Table table = new Table();
        table.database = database;
        table.name = tableName.toLowerCase();
        table.recordCount = metadata.getRecordCount();
        table.primaryKey = metadata.getPrimaryKey();
        
        // Reconstruct attributes from metadata
        table.attributes = new ArrayList<>();
        List<String> attrNames = metadata.getAttributeNames();
        List<DataType> attrTypes = metadata.getAttributeTypes();
        List<Boolean> isPK = metadata.getIsPrimaryKey();
        
        for (int i = 0; i < attrNames.size(); i++) {
            Attribute attr = new Attribute(attrNames.get(i), attrTypes.get(i), isPK.get(i));
            table.attributes.add(attr);
        }
        
        // Set table path
        table.tablePath = database.getPath() + File.separator + Database.TABLES_DIR + File.separator + table.name;
        table.dataFileName = table.tablePath + File.separator + "data.dat";
        table.indexFileName = table.tablePath + File.separator + "index.idx";
        
        // Initialize files
        File dataFileObj = new File(table.dataFileName);
        if (!dataFileObj.exists()) {
            return null;
        }
        
        table.dataFile = new RandomAccessFile(table.dataFileName, "rw");
        table.index = new BSTIndex(table.name, table.primaryKey);
        
        // Load index if exists
        File idxFile = new File(table.indexFileName);
        if (idxFile.exists()) {
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(idxFile))) {
                table.index = (BSTIndex) ois.readObject();
            }
        } else if (table.primaryKey != null) {
            table.rebuildIndexFromData();
        }
        
        return table;
    }

    public Record getRecordAtPosition(long position) throws Exception {
        return readRecordAtPosition(position);
    }

    private void initFiles() throws Exception {
        // Create or open data file
        dataFile = new RandomAccessFile(dataFileName, "rw");

        // Write header if file is new
        if (dataFile.length() == 0) {
            writeHeader();
        }

        // Initialize index
        index = new BSTIndex(name, primaryKey);
        
        // Load or create index
        loadIndex();
    }

    private void writeHeader() throws Exception {
        // Write number of attributes (4 bytes)
        dataFile.writeInt(attributes.size());

        // Write attribute information
        for (Attribute attr : attributes) {
            dataFile.writeUTF(attr.getName());
            dataFile.writeInt(attr.getType().ordinal());
            dataFile.writeBoolean(attr.isPrimaryKey());
        }

        // Write record count (4 bytes)
        dataFile.writeInt(0);
    }

    private void loadIndex() throws Exception {
        File idxFile = new File(indexFileName);
        if (idxFile.exists()) {
            // Load existing index
            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(idxFile))) {
                index = (BSTIndex) ois.readObject();
            }
        } else {
            // Create new index if table has primary key
            if (primaryKey != null) {
                rebuildIndexFromData();
                saveIndex();
            }
        }
    }

    private void rebuildIndexFromData() throws Exception {
        if (primaryKey == null) return;

        index = new BSTIndex(name, primaryKey);
        long currentPos = getFirstRecordPosition();

        while (currentPos < dataFile.length()) {
            dataFile.seek(currentPos);
            try {
                byte marker = dataFile.readByte();
                if (marker == 0x01) { // Active record
                    // Read the record to get key value
                    Record record = readRecordAtCurrentPosition();
                    if (record != null) {
                        Object keyValue = record.getValue(primaryKey);
                        if (keyValue instanceof Comparable) {
                            index.insert((Comparable) keyValue, currentPos);
                        }
                    }
                }
                currentPos = dataFile.getFilePointer();
            } catch (Exception e) {
                break;
            }
        }
    }

    private long getFirstRecordPosition() throws Exception {
        dataFile.seek(0);

        // Read number of attributes
        int numAttrs = dataFile.readInt();

        // Skip attribute definitions
        for (int i = 0; i < numAttrs; i++) {
            dataFile.readUTF(); // name
            dataFile.readInt(); // type ordinal
            dataFile.readBoolean(); // isPrimaryKey
        }

        // Skip record count
        dataFile.readInt();

        // Return position of first record
        return dataFile.getFilePointer();
    }

    private void parseAttributes(List<String> tokens) throws Exception {
        for (int i = 0; i < tokens.size(); i++) {
            String attrName = tokens.get(i);
            if (!isValidIdentifier(attrName)) {
                throw new Exception("Invalid attribute name: " + attrName);
            }

            i++;

            if (i >= tokens.size()) {
                throw new Exception("Missing data type for attribute " + attrName);
            }

            String dataType = tokens.get(i).toUpperCase();
            DataType type;

            switch (dataType) {
                case "INTEGER":
                    type = DataType.INTEGER;
                    break;
                case "TEXT":
                    type = DataType.TEXT;
                    break;
                case "FLOAT":
                    type = DataType.FLOAT;
                    break;
                default:
                    throw new Exception("Invalid data type: " + dataType);
            }

            boolean isPrimaryKey = false;

            // Check for PRIMARY KEY keyword
            if (i + 1 < tokens.size() && tokens.get(i + 1).toUpperCase().equals("PRIMARY") &&
                    i + 2 < tokens.size() && tokens.get(i + 2).toUpperCase().equals("KEY")) {

                if (!attributes.isEmpty()) {
                    throw new Exception("PRIMARY KEY must be on first attribute");
                }

                isPrimaryKey = true;
                i += 2; // Skip "PRIMARY" and "KEY"
            }

            Attribute attr = new Attribute(attrName, type, isPrimaryKey);
            attributes.add(attr);

            if (isPrimaryKey) {
                if (primaryKey != null) {
                    throw new Exception("Multiple PRIMARY KEY attributes not allowed");
                }
                primaryKey = attrName.toLowerCase();
            }

            // Check for comma
            if (i + 1 < tokens.size() && tokens.get(i + 1).equals(",")) {
                i++; // Skip comma
            }
        }

        if (attributes.isEmpty()) {
            throw new Exception("Table must have at least one attribute");
        }
    }

    private boolean isValidIdentifier(String identifier) {
        if (identifier == null || identifier.isEmpty() || identifier.length() > 19) {
            return false;
        }

        char firstChar = identifier.charAt(0);
        if (!Character.isLetter(firstChar)) {
            return false;
        }

        for (int i = 1; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            if (!Character.isLetterOrDigit(c) && c != '_') {
                return false;
            }
        }

        return true;
    }

    public void insert(List<String> valueTokens) throws Exception {
        if (valueTokens.size() != attributes.size()) {
            throw new Exception("Expected " + attributes.size() + " values, got " + valueTokens.size());
        }

        Record record = new Record();

        // Parse and validate each value
        for (int i = 0; i < attributes.size(); i++) {
            Attribute attr = attributes.get(i);
            String valueStr = valueTokens.get(i);

            Object parsedValue = attr.parseValue(valueStr);
            record.setValue(attr.getName(), parsedValue);
        }

        // Check primary key constraint
        if (primaryKey != null) {
            Object keyValue = record.getValue(primaryKey);

            // Check if key already exists
            if (index != null && index.search((Comparable) keyValue) != null) {
                throw new Exception("Duplicate primary key value: " + keyValue);
            }

            // Check entity integrity (NOT NULL)
            if (keyValue == null) {
                throw new Exception("Primary key cannot be null");
            }
        }

        // Write record to file
        long position = writeRecord(record);

        // Update index if primary key exists
        if (primaryKey != null && index != null) {
            Object keyValue = record.getValue(primaryKey);
            index.insert((Comparable) keyValue, position);
            saveIndex();
        }

        recordCount++;
        updateRecordCount();
        System.out.println("1 record inserted");
    }

    private long writeRecord(Record record) throws Exception {
        // Seek to end of file
        long position = dataFile.length();
        dataFile.seek(position);

        // Write record marker - THIS is the record start position we'll store in index
        dataFile.writeByte(0x01); // Record start marker

        // Write each attribute value
        for (Attribute attr : attributes) {
            Object value = record.getValue(attr.getName());

            switch (attr.getType()) {
                case INTEGER:
                    dataFile.writeInt((Integer) value);
                    break;
                case FLOAT:
                    dataFile.writeDouble((Double) value);
                    break;
                case TEXT:
                    String strValue = (String) value;
                    if (strValue.length() > 100) {
                        strValue = strValue.substring(0, 100);
                    }
                    dataFile.writeUTF(strValue);
                    break;
            }
        }

        // Write record end marker
        dataFile.writeByte(0x02);

        return position; // Return the position of the start marker
    }

    public Record readRecordAtPosition(long position) throws Exception {
        dataFile.seek(position);
        return readRecordAtCurrentPosition();
    }

    private Record readRecordAtCurrentPosition() throws Exception {
        // Check record marker
        byte marker;
        try {
            marker = dataFile.readByte();
        } catch (EOFException e) {
            return null;
        }

        if (marker != 0x01) {
            return null;
        }

        Record record = new Record();

        // Read each attribute value
        for (Attribute attr : attributes) {
            switch (attr.getType()) {
                case INTEGER:
                    record.setValue(attr.getName(), dataFile.readInt());
                    break;
                case FLOAT:
                    record.setValue(attr.getName(), dataFile.readDouble());
                    break;
                case TEXT:
                    record.setValue(attr.getName(), dataFile.readUTF());
                    break;
            }
        }

        // Check end marker
        try {
            marker = dataFile.readByte();
            if (marker != 0x02) {
                throw new Exception("Corrupted record: missing end marker");
            }
        } catch (EOFException e) {
            throw new Exception("Corrupted record: EOF before end marker");
        }

        return record;
    }

    private void updateRecordCount() throws Exception {
        long currentPos = dataFile.getFilePointer();

        dataFile.seek(0);
        int numAttrs = dataFile.readInt();

        // Skip attribute definitions
        for (int i = 0; i < numAttrs; i++) {
            dataFile.readUTF();
            dataFile.readInt();
            dataFile.readBoolean();
        }

        // Update record count
        dataFile.writeInt(recordCount);

        // Return to previous position
        dataFile.seek(currentPos);
    }

    private void saveIndex() throws Exception {
        if (index != null) {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(indexFileName))) {
                oos.writeObject(index);
            }
        }
    }

    public void select(List<String> attributeNames, Condition whereCondition) throws Exception {
        List<Record> results = new ArrayList<>();
        List<Long> recordPositions;

        // Determine access path based on where condition and primary key
        if (whereCondition != null && whereCondition.isKeyCondition(primaryKey)) {
            // Use index for primary key equality
            Object keyValue = whereCondition.getConstantValue();
            Attribute keyAttr = getAttribute(primaryKey);
            Object parsedKey = keyAttr.parseValue(keyValue.toString());
            Long position = index.search((Comparable) parsedKey);
            if (position != null) {
                recordPositions = new ArrayList<>();
                recordPositions.add(position);
            } else {
                recordPositions = new ArrayList<>();
            }
        } else if (primaryKey != null && index != null) {
            // Use in-order traversal for non-key conditions
            recordPositions = index.inOrderTraversal();
        } else {
            // No primary key - just scan all records in file order
            recordPositions = getAllRecordPositions();
        }

        // Retrieve and filter records
        for (Long pos : recordPositions) {
            try {
                Record record = readRecordAtPosition(pos);
                if (record != null) {
                    if (whereCondition == null || whereCondition.evaluate(record, attributes)) {
                        results.add(record);
                    }
                }
            } catch (Exception e) {
                // Skip corrupted records
            }
        }

        // Display results
        displayRecords(results, attributeNames);
    }

    public List<Long> getAllRecordPositions() throws Exception {
        List<Long> positions = new ArrayList<>();
        long currentPos = getFirstRecordPosition();

        while (currentPos < dataFile.length()) {
            dataFile.seek(currentPos);

            try {
                byte marker = dataFile.readByte();
                if (marker == 0x01) { // Only add active records
                    positions.add(currentPos);

                    // Skip to next record
                    for (Attribute attr : attributes) {
                        switch (attr.getType()) {
                            case INTEGER:
                                dataFile.readInt();
                                break;
                            case FLOAT:
                                dataFile.readDouble();
                                break;
                            case TEXT:
                                dataFile.readUTF();
                                break;
                        }
                    }

                    // Check end marker
                    marker = dataFile.readByte();
                    if (marker != 0x02) {
                        break;
                    }

                    currentPos = dataFile.getFilePointer();
                } else if (marker == 0x00) {
                    // Deleted record - skip it
                    for (Attribute attr : attributes) {
                        switch (attr.getType()) {
                            case INTEGER:
                                dataFile.readInt();
                                break;
                            case FLOAT:
                                dataFile.readDouble();
                                break;
                            case TEXT:
                                dataFile.readUTF();
                                break;
                        }
                    }
                    dataFile.readByte(); // Skip end marker
                    currentPos = dataFile.getFilePointer();
                } else {
                    currentPos++;
                }
            } catch (EOFException e) {
                break;
            }
        }

        return positions;
    }

    public void displayRecords(List<Record> records, List<String> attributeNames) {
        if (records.isEmpty()) {
            System.out.println("Nothing found");
            return;
        }

        // Determine which attributes to display
        List<String> displayAttrs;
        if (attributeNames.size() == 1 && attributeNames.get(0).equals("*")) {
            // Show all attributes
            displayAttrs = new ArrayList<>();
            for (Attribute attr : attributes) {
                displayAttrs.add(attr.getName());
            }
        } else {
            displayAttrs = attributeNames;
        }

        // Display header
        for (int i = 0; i < displayAttrs.size(); i++) {
            if (i > 0) System.out.print(" | ");
            System.out.print(displayAttrs.get(i).toUpperCase());
        }
        System.out.println();
        System.out.println("-".repeat(50));

        // Display records
        int rowNum = 1;
        for (Record record : records) {
            System.out.print(rowNum + ". ");
            for (int i = 0; i < displayAttrs.size(); i++) {
                if (i > 0) System.out.print(" | ");
                Object value = record.getValue(displayAttrs.get(i));
                if (value == null) {
                    System.out.print("null");
                } else if (value instanceof String) {
                    System.out.print("\"" + value + "\"");
                } else {
                    System.out.print(value);
                }
            }
            System.out.println();
            rowNum++;
        }
    }

    public int delete(Condition whereCondition) throws Exception {
        List<Long> recordsToDelete = new ArrayList<>();
        List<Long> recordPositions;

        // If no WHERE clause, delete all records
        if (whereCondition == null) {
            // Delete all records and clear the table
            dataFile.setLength(getFirstRecordPosition()); // Truncate file after header
            int deletedCount = recordCount;
            recordCount = 0;
            updateRecordCount();

            // Clear the index if it exists
            if (index != null) {
                index = new BSTIndex(name, primaryKey);
                saveIndex();
            }

            return deletedCount;
        }

        // Determine access path based on where condition and primary key
        if (whereCondition.isKeyCondition(primaryKey)) {
            // Use index for primary key equality
            Object keyValue = whereCondition.getConstantValue();
            Attribute keyAttr = getAttribute(primaryKey);
            Object parsedKey = keyAttr.parseValue(keyValue.toString());
            Long position = index.search((Comparable) parsedKey);
            if (position != null) {
                recordsToDelete.add(position);
            }
        } else if (primaryKey != null && index != null) {
            // Use in-order traversal for non-key conditions
            recordPositions = index.inOrderTraversal();

            // Check each record against condition
            for (Long pos : recordPositions) {
                Record record = readRecordAtPosition(pos);
                if (record != null && whereCondition.evaluate(record, attributes)) {
                    recordsToDelete.add(pos);
                }
            }
        } else {
            // No primary key - scan all records
            recordPositions = getAllRecordPositions();

            for (Long pos : recordPositions) {
                Record record = readRecordAtPosition(pos);
                if (record != null && whereCondition.evaluate(record, attributes)) {
                    recordsToDelete.add(pos);
                }
            }
        }

        // Delete the records (mark as deleted)
        int deletedCount = 0;
        for (Long pos : recordsToDelete) {
            if (deleteRecordAtPosition(pos)) {
                deletedCount++;
            }
        }

        // If records were deleted, compact file and rebuild index
        if (deletedCount > 0) {
            compactFile();
            recordCount -= deletedCount;
            updateRecordCount();

            // Rebuild index
            if (index != null) {
                index = new BSTIndex(name, primaryKey);
                rebuildIndexFromData();
                saveIndex();
            }
        }

        return deletedCount;
    }

    private boolean deleteRecordAtPosition(long position) throws Exception {
        dataFile.seek(position);
        byte marker = dataFile.readByte();

        if (marker != 0x01) {
            return false; // Not a valid record start
        }

        // Mark as deleted (change marker to 0x00)
        dataFile.seek(position);
        dataFile.writeByte(0x00); // Deleted marker

        return true;
    }

    private void compactFile() throws Exception {
        // Create a temporary file
        String tempFileName = tablePath + File.separator + "temp.dat";
        RandomAccessFile tempFile = new RandomAccessFile(tempFileName, "rw");

        // Copy header to temp file
        dataFile.seek(0);
        byte[] header = new byte[(int) getFirstRecordPosition()];
        dataFile.readFully(header);
        tempFile.write(header);

        // Copy only active records (marker 0x01)
        List<Long> activePositions = getAllRecordPositions();
        for (Long pos : activePositions) {
            dataFile.seek(pos);

            // Read the entire record
            byte marker = dataFile.readByte();
            if (marker != 0x01) continue;

            // Write marker
            tempFile.writeByte(0x01);

            // Copy attribute values
            for (Attribute attr : attributes) {
                switch (attr.getType()) {
                    case INTEGER:
                        tempFile.writeInt(dataFile.readInt());
                        break;
                    case FLOAT:
                        tempFile.writeDouble(dataFile.readDouble());
                        break;
                    case TEXT:
                        tempFile.writeUTF(dataFile.readUTF());
                        break;
                }
            }

            tempFile.writeByte(0x02); // Write end marker
        }

        // Close and replace files
        tempFile.close();
        dataFile.close();

        // Rename temp file to original
        new File(tempFileName).renameTo(new File(dataFileName));

        // Reopen data file
        dataFile = new RandomAccessFile(dataFileName, "rw");
    }

    public int update(Map<String, String> setClauses, Condition whereCondition) throws Exception {
        List<Long> recordsToUpdate = new ArrayList<>();
        List<Long> recordPositions;

        // Parse set clauses to get new values
        Map<String, Object> newValues = new HashMap<>();
        for (Map.Entry<String, String> clause : setClauses.entrySet()) {
            String attrName = clause.getKey().toLowerCase();
            String valueStr = clause.getValue();

            Attribute attr = getAttribute(attrName);
            if (attr == null) {
                throw new Exception("Unknown attribute: " + attrName);
            }

            Object parsedValue = attr.parseValue(valueStr);
            newValues.put(attrName, parsedValue);
        }

        // Check if updating primary key
        boolean updatingPrimaryKey = primaryKey != null && newValues.containsKey(primaryKey);

        // Determine which records to update
        if (whereCondition == null) {
            // Update all records
            recordPositions = getAllRecordPositions();
            recordsToUpdate.addAll(recordPositions);
        } else if (whereCondition.isKeyCondition(primaryKey)) {
            // Use index for primary key equality
            Object keyValue = whereCondition.getConstantValue();
            Attribute keyAttr = getAttribute(primaryKey);
            Object parsedKey = keyAttr.parseValue(keyValue.toString());
            Long position = index.search((Comparable) parsedKey);
            if (position != null) {
                recordsToUpdate.add(position);
            }
        } else if (primaryKey != null && index != null) {
            // Use in-order traversal for non-key conditions
            recordPositions = index.inOrderTraversal();

            for (Long pos : recordPositions) {
                Record record = readRecordAtPosition(pos);
                if (record != null && whereCondition.evaluate(record, attributes)) {
                    recordsToUpdate.add(pos);
                }
            }
        } else {
            // No primary key - scan all records
            recordPositions = getAllRecordPositions();

            for (Long pos : recordPositions) {
                Record record = readRecordAtPosition(pos);
                if (record != null && whereCondition.evaluate(record, attributes)) {
                    recordsToUpdate.add(pos);
                }
            }
        }

        // Update records
        int updatedCount = 0;
        for (Long pos : recordsToUpdate) {
            if (updateRecordAtPosition(pos, newValues, updatingPrimaryKey)) {
                updatedCount++;
            }
        }

        // If primary key was updated, rebuild index
        if (updatingPrimaryKey && updatedCount > 0 && index != null) {
            index = new BSTIndex(name, primaryKey);
            rebuildIndexFromData();
            saveIndex();
        }

        return updatedCount;
    }

    private boolean updateRecordAtPosition(long position, Map<String, Object> newValues, boolean updatingPrimaryKey) throws Exception {
        // Read the current record
        Record record = readRecordAtPosition(position);
        if (record == null) {
            return false;
        }

        // Check primary key constraint if updating primary key
        if (updatingPrimaryKey) {
            Object newKeyValue = newValues.get(primaryKey);

            // Check if new key already exists (and it's not the same record)
            Long existingPos = index.search((Comparable) newKeyValue);
            if (existingPos != null && existingPos != position) {
                throw new Exception("Duplicate primary key value: " + newKeyValue);
            }
        }

        // Update the record values
        for (Map.Entry<String, Object> entry : newValues.entrySet()) {
            record.setValue(entry.getKey(), entry.getValue());
        }

        // Write the updated record (mark as deleted and append new version)
        deleteRecordAtPosition(position); // Mark old record as deleted
        long newPosition = writeRecord(record); // Write new version

        // Update index with new position if primary key exists and wasn't updated
        if (primaryKey != null && index != null && !updatingPrimaryKey) {
            Object keyValue = record.getValue(primaryKey);
            index.insert((Comparable) keyValue, newPosition);
            saveIndex();
        }

        return true;
    }

    public void renameAttributes(List<String> newAttrNames) throws Exception {
        if (newAttrNames.size() != attributes.size()) {
            throw new Exception("Number of new attribute names (" + newAttrNames.size() +
                    ") does not match existing attributes (" + attributes.size() + ")");
        }

        // Validate new attribute names
        for (String newName : newAttrNames) {
            if (!isValidIdentifier(newName)) {
                throw new Exception("Invalid attribute name: " + newName);
            }
        }

        // Check for duplicates
        Set<String> uniqueNames = new HashSet<>(newAttrNames);
        if (uniqueNames.size() != newAttrNames.size()) {
            throw new Exception("Duplicate attribute names in RENAME list");
        }

        // Update attribute names
        String oldPrimaryKey = primaryKey;
        primaryKey = null;

        List<Attribute> newAttributes = new ArrayList<>();
        for (int i = 0; i < attributes.size(); i++) {
            Attribute oldAttr = attributes.get(i);
            String newName = newAttrNames.get(i).toLowerCase();

            boolean isPK = oldAttr.isPrimaryKey();
            Attribute newAttr = new Attribute(newName, oldAttr.getType(), isPK);
            newAttributes.add(newAttr);

            if (isPK) {
                primaryKey = newName;
            }
        }

        // Update the table header in the data file
        long currentPos = dataFile.getFilePointer();
        dataFile.seek(0);
        dataFile.writeInt(newAttributes.size());

        for (Attribute attr : newAttributes) {
            dataFile.writeUTF(attr.getName());
            dataFile.writeInt(attr.getType().ordinal());
            dataFile.writeBoolean(attr.isPrimaryKey());
        }

        // Preserve record count
        dataFile.writeInt(recordCount);

        // Update attributes list
        attributes = newAttributes;

        // Update index if primary key changed
        if (primaryKey != null && !primaryKey.equals(oldPrimaryKey) && index != null) {
            index = new BSTIndex(name, primaryKey);
            rebuildIndexFromData();
            saveIndex();
        }

        // Return to previous position
        dataFile.seek(currentPos);
    }

    public void selectAggregate(String function, String attribute, Condition whereCondition) throws Exception {
        List<Record> records;
        List<Long> recordPositions;

        // Get records based on primary key and condition
        if (whereCondition != null && whereCondition.isKeyCondition(primaryKey)) {
            Object keyValue = whereCondition.getConstantValue();
            Attribute keyAttr = getAttribute(primaryKey);
            Object parsedKey = keyAttr.parseValue(keyValue.toString());
            Long position = index.search((Comparable) parsedKey);
            recordPositions = position != null ? Collections.singletonList(position) : new ArrayList<>();
        } else if (primaryKey != null && index != null) {
            recordPositions = index.inOrderTraversal();
        } else {
            recordPositions = getAllRecordPositions();
        }

        records = new ArrayList<>();
        for (Long pos : recordPositions) {
            Record record = readRecordAtPosition(pos);
            if (record != null) {
                if (whereCondition == null || whereCondition.evaluate(record, attributes)) {
                    records.add(record);
                }
            }
        }

        // Compute aggregate
        switch (function.toUpperCase()) {
            case "COUNT":
                if (attribute.equalsIgnoreCase("*")) {
                    System.out.println("COUNT");
                    System.out.println("-".repeat(50));
                    System.out.println("1. " + records.size());
                } else {
                    int count = 0;
                    for (Record record : records) {
                        Object val = record.getValue(attribute);
                        if (val != null) {
                            count++;
                        }
                    }
                    System.out.println("COUNT(" + attribute + ")");
                    System.out.println("-".repeat(50));
                    System.out.println("1. " + count);
                }
                break;

            case "MIN":
                Object min = null;
                for (Record record : records) {
                    Object val = record.getValue(attribute);
                    if (val != null) {
                        if (min == null || compareValues(val, min, "<")) {
                            min = val;
                        }
                    }
                }
                System.out.println("MIN(" + attribute + ")");
                System.out.println("-".repeat(50));
                if (min instanceof String) {
                    System.out.println("1. \"" + min + "\"");
                } else {
                    System.out.println("1. " + min);
                }
                break;

            case "MAX":
                Object max = null;
                for (Record record : records) {
                    Object val = record.getValue(attribute);
                    if (val != null) {
                        if (max == null || compareValues(val, max, ">")) {
                            max = val;
                        }
                    }
                }
                System.out.println("MAX(" + attribute + ")");
                System.out.println("-".repeat(50));
                if (max instanceof String) {
                    System.out.println("1. \"" + max + "\"");
                } else {
                    System.out.println("1. " + max);
                }
                break;

            case "AVG":
                if (attribute.equalsIgnoreCase("*")) {
                    throw new Exception("AVG cannot be used with *");
                }

                // Check if attribute is numeric
                Attribute attr = getAttribute(attribute);
                if (attr == null) {
                    throw new Exception("Unknown attribute: " + attribute);
                }
                if (attr.getType() == DataType.TEXT) {
                    throw new Exception("AVG cannot be used with TEXT attribute");
                }

                double sum = 0;
                int count = 0;
                for (Record record : records) {
                    Object val = record.getValue(attribute);
                    if (val != null) {
                        sum += ((Number) val).doubleValue();
                        count++;
                    }
                }
                double avg = count > 0 ? sum / count : 0;
                System.out.println("AVG(" + attribute + ")");
                System.out.println("-".repeat(50));
                System.out.println("1. " + avg);
                break;

            default:
                throw new Exception("Unknown aggregate function: " + function);
        }
    }

    @SuppressWarnings("unchecked")
    private boolean compareValues(Object left, Object right, String op) {
        if (left instanceof String && right instanceof String) {
            String leftStr = (String) left;
            String rightStr = (String) right;
            int cmp = leftStr.compareToIgnoreCase(rightStr);
            switch (op) {
                case "<": return cmp < 0;
                case ">": return cmp > 0;
                default: return false;
            }
        }
        if (left instanceof Number && right instanceof Number) {
            double leftNum = ((Number) left).doubleValue();
            double rightNum = ((Number) right).doubleValue();
            switch (op) {
                case "<": return leftNum < rightNum;
                case ">": return leftNum > rightNum;
                default: return false;
            }
        }
        return false;
    }

    public BSTIndex getIndex() {
        return index;
    }

    public TableMetadata getMetadata() {
        return new TableMetadata(name, attributes, primaryKey, recordCount);
    }

    public void describe() {
        System.out.println(name.toUpperCase());
        for (Attribute attr : attributes) {
            System.out.print("  " + attr.getName() + ": " + attr.getType());
            if (attr.isPrimaryKey()) {
                System.out.print(" PRIMARY KEY");
            }
            System.out.println();
        }
    }

    public String getName() {
        return name;
    }

    public List<Attribute> getAttributes() {
        return attributes;
    }

    public String getPrimaryKey() {
        return primaryKey;
    }

    public Attribute getAttribute(String name) {
        String searchName = name.toLowerCase();
        for (Attribute attr : attributes) {
            if (attr.getName().equals(searchName)) {
                return attr;
            }
        }
        return null;
    }

    public void close() throws Exception {
        if (dataFile != null) {
            dataFile.close();
        }
        saveIndex();
    }

    public String getTablePath() {
        return tablePath;
    }
}
