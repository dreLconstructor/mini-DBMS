import java.io.*;
import java.util.*;

public class MiniDBMS2 {
    private static Scanner scanner = new Scanner(System.in);
    private static String currentDatabase = null;
    private static Map<String, Database> databases = new HashMap<>();

    public static void main(String[] args) {
        System.out.println("=====================================");
        System.out.println("     MiniDBMS v3.0 - Phase 3");
        System.out.println("=====================================");
        System.out.println("Supported commands:");
        System.out.println("  CREATE DATABASE dbname;");
        System.out.println("  USE dbname;");
        System.out.println("  CREATE TABLE tablename (attr domain [PRIMARY KEY], ...);");
        System.out.println("  INSERT INTO tablename VALUES (val1, val2, ...);");
        System.out.println("  SELECT attr1, attr2, ... FROM tablename [WHERE condition];");
        System.out.println("  SELECT count(*)|min(attr)|max(attr)|avg(attr) FROM tablename [WHERE condition];");
        System.out.println("  UPDATE tablename SET attr = value [, attr = value]* [WHERE condition];");
        System.out.println("  DELETE tablename [WHERE condition];");
        System.out.println("  RENAME tablename (attr1, attr2, ...);");
        System.out.println("  LET tablename KEY attrname SELECT ...;");
        System.out.println("  INPUT filename [OUTPUT filename];");
        System.out.println("  DESCRIBE tablename;");
        System.out.println("  DESCRIBE ALL;");
        System.out.println("  EXIT;");
        System.out.println("=====================================");
        System.out.println();

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                for (Database db : databases.values()) {
                    db.close();
                }
            } catch (Exception e) {
                System.err.println("Error during shutdown: " + e.getMessage());
            }
        }));

        while (true) {
            System.out.print("> ");
            String input = readMultilineInput();

            if (input == null || input.trim().isEmpty()) {
                continue;
            }

            try {
                processCommand(input);
            } catch (Exception e) {
                System.out.println("Error: " + e.getMessage());
            }
        }
    }

    private static String readMultilineInput() {
        StringBuilder sb = new StringBuilder();
        String line;
        boolean inMultiLine = false;

        while (true) {
            if (!inMultiLine) {
                if (!scanner.hasNextLine()) {
                    return null;
                }
                line = scanner.nextLine();
            } else {
                System.out.print("... ");
                if (!scanner.hasNextLine()) {
                    return null;
                }
                line = scanner.nextLine();
            }

            if (sb.length() == 0) {
                sb.append(line);
            } else {
                sb.append(" ").append(line);
            }

            if (line.trim().endsWith(";")) {
                break;
            }
            inMultiLine = true;
        }

        return sb.toString();
    }

    private static void processCommand(String input) throws Exception {
        input = input.substring(0, input.lastIndexOf(';')).trim();
        List<String> tokens = tokenize(input);

        if (tokens.isEmpty()) {
            return;
        }

        String command = tokens.get(0).toUpperCase();

        switch (command) {
            case "CREATE":
                parseCreate(tokens);
                break;
            case "USE":
                parseUse(tokens);
                break;
            case "DESCRIBE":
                parseDescribe(tokens);
                break;
            case "INSERT":
                parseInsert(tokens);
                break;
            case "SELECT":
                parseSelect(tokens);
                break;
            case "UPDATE":
                parseUpdate(tokens);
                break;
            case "DELETE":
                parseDelete(tokens);
                break;
            case "RENAME":
                parseRename(tokens);
                break;
            case "LET":
                parseLet(tokens);
                break;
            case "INPUT":
                parseInput(tokens);
                break;
            case "EXIT":
                System.out.println("Goodbye!");
                for (Database db : databases.values()) {
                    db.close();
                }
                System.exit(0);
                break;
            default:
                System.out.println("Unknown command: " + command);
        }
    }

    private static List<String> tokenize(String input) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inQuotes = false;

        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);

            if (c == '"') {
                inQuotes = !inQuotes;
                current.append(c);
            } else if (Character.isWhitespace(c) && !inQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current = new StringBuilder();
                }
            } else if ((c == '(' || c == ')' || c == ',' || c == '=' || c == '!' || c == '<' || c == '>') && !inQuotes) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current = new StringBuilder();
                }

                if ((c == '!' || c == '<' || c == '>') && i + 1 < input.length() && input.charAt(i + 1) == '=') {
                    tokens.add(String.valueOf(c) + "=");
                    i++;
                } else {
                    tokens.add(String.valueOf(c));
                }
            } else {
                current.append(c);
            }
        }

        if (current.length() > 0) {
            tokens.add(current.toString());
        }

        return tokens;
    }

    private static void parseCreate(List<String> tokens) throws Exception {
        if (tokens.size() < 2) {
            throw new Exception("Invalid CREATE command");
        }

        String type = tokens.get(1).toUpperCase();

        if (type.equals("DATABASE")) {
            if (tokens.size() < 3) {
                throw new Exception("CREATE DATABASE requires a name");
            }
            String dbName = tokens.get(2);
            createDatabase(dbName);
        } else if (type.equals("TABLE")) {
            if (currentDatabase == null) {
                throw new Exception("No database selected");
            }
            parseCreateTable(tokens);
        } else {
            throw new Exception("Unknown CREATE type: " + type);
        }
    }

    private static void parseCreateTable(List<String> tokens) throws Exception {
        if (tokens.size() < 4 || !tokens.get(3).equals("(")) {
            throw new Exception("Invalid CREATE TABLE syntax");
        }

        String tableName = tokens.get(2);

        List<String> attrTokens = new ArrayList<>();
        int i = 4;
        while (i < tokens.size() && !tokens.get(i).equals(")")) {
            attrTokens.add(tokens.get(i));
            i++;
        }

        if (i >= tokens.size()) {
            throw new Exception("Missing closing parenthesis in CREATE TABLE");
        }

        Database db = databases.get(currentDatabase);
        if (db == null) {
            db = new Database(currentDatabase);
            databases.put(currentDatabase, db);
        }

        db.createTable(tableName, attrTokens);
    }

    private static void parseInsert(List<String> tokens) throws Exception {
        if (currentDatabase == null) {
            throw new Exception("No database selected");
        }

        if (tokens.size() < 3 || !tokens.get(1).toUpperCase().equals("INTO")) {
            throw new Exception("Invalid INSERT syntax. Use: INSERT INTO tablename VALUES (...);");
        }

        String tableName = tokens.get(2);

        int valuesIndex = -1;
        for (int i = 3; i < tokens.size(); i++) {
            if (tokens.get(i).toUpperCase().equals("VALUES")) {
                valuesIndex = i;
                break;
            }
        }

        if (valuesIndex == -1 || valuesIndex + 1 >= tokens.size() || !tokens.get(valuesIndex + 1).equals("(")) {
            throw new Exception("Invalid INSERT syntax. Missing VALUES clause.");
        }

        List<String> valueTokens = new ArrayList<>();
        int i = valuesIndex + 2;

        while (i < tokens.size()) {
            String token = tokens.get(i);

            if (token.equals(")")) {
                break;
            }

            if (token.equals(",")) {
                i++;
                continue;
            }

            valueTokens.add(token);
            i++;
        }

        Database db = databases.get(currentDatabase);
        db.insertInto(tableName, valueTokens);
    }

    private static void parseSelect(List<String> tokens) throws Exception {
        if (currentDatabase == null) {
            throw new Exception("No database selected");
        }

        // Check for aggregate functions
        if (tokens.size() > 1) {
            String firstToken = tokens.get(1).toUpperCase();
            if (firstToken.equals("COUNT") || firstToken.equals("MIN") ||
                    firstToken.equals("MAX") || firstToken.equals("AVG")) {
                parseAggregateSelect(tokens);
                return;
            }
        }

        // Regular SELECT parsing
        List<String> attrList = new ArrayList<>();
        int i = 1;

        if (i < tokens.size() && tokens.get(i).equals("*")) {
            attrList.add("*");
            i++;
        } else {
            while (i < tokens.size() && !tokens.get(i).toUpperCase().equals("FROM")) {
                if (!tokens.get(i).equals(",")) {
                    attrList.add(tokens.get(i));
                }
                i++;
            }
        }

        if (i >= tokens.size() || !tokens.get(i).toUpperCase().equals("FROM")) {
            throw new Exception("Invalid SELECT syntax. Missing FROM clause.");
        }

        i++;

        if (i >= tokens.size()) {
            throw new Exception("Invalid SELECT syntax. Missing table name.");
        }
        String tableName = tokens.get(i);
        i++;

        Condition whereCondition = null;
        if (i < tokens.size() && tokens.get(i).toUpperCase().equals("WHERE")) {
            i++;
            if (i + 2 >= tokens.size()) {
                throw new Exception("Invalid WHERE clause");
            }

            String leftAttr = tokens.get(i);
            String operator = tokens.get(i + 1);
            String right = tokens.get(i + 2);

            boolean isConstant = determineIfConstant(right);
            whereCondition = new Condition(leftAttr, operator, right, isConstant);
        }

        Database db = databases.get(currentDatabase);
        db.select(tableName, attrList, whereCondition);
    }

    private static void parseAggregateSelect(List<String> tokens) throws Exception {
        String function = tokens.get(1).toUpperCase();
        String attribute;
        int i = 2;

        // Handle count(*) specially
        if (function.equals("COUNT") && tokens.get(2).equals("(*)")) {
            attribute = "*";
            i = 3;
        } else {
            // Parse attribute name (might be in parentheses)
            if (tokens.get(2).startsWith("(")) {
                attribute = tokens.get(2).substring(1);
                if (attribute.endsWith(")")) {
                    attribute = attribute.substring(0, attribute.length() - 1);
                }
                i = 3;
            } else {
                attribute = tokens.get(2);
                i = 3;
                // Skip closing parenthesis if present
                if (i < tokens.size() && tokens.get(i).equals(")")) {
                    i++;
                }
            }
        }

        // Find FROM clause
        while (i < tokens.size() && !tokens.get(i).toUpperCase().equals("FROM")) {
            i++;
        }

        if (i >= tokens.size() || !tokens.get(i).toUpperCase().equals("FROM")) {
            throw new Exception("Invalid SELECT syntax. Missing FROM clause.");
        }

        i++;

        if (i >= tokens.size()) {
            throw new Exception("Invalid SELECT syntax. Missing table name.");
        }

        String tableName = tokens.get(i);
        i++;

        // Parse WHERE clause if present
        Condition whereCondition = null;
        if (i < tokens.size() && tokens.get(i).toUpperCase().equals("WHERE")) {
            i++;
            if (i + 2 >= tokens.size()) {
                throw new Exception("Invalid WHERE clause");
            }

            String leftAttr = tokens.get(i);
            String operator = tokens.get(i + 1);
            String right = tokens.get(i + 2);

            boolean isConstant = determineIfConstant(right);
            whereCondition = new Condition(leftAttr, operator, right, isConstant);
        }

        Database db = databases.get(currentDatabase);
        Table table = db.getTable(tableName);
        table.selectAggregate(function, attribute, whereCondition);
    }

    private static void parseUpdate(List<String> tokens) throws Exception {
        if (currentDatabase == null) {
            throw new Exception("No database selected");
        }

        if (tokens.size() < 4 || !tokens.get(2).toUpperCase().equals("SET")) {
            throw new Exception("Invalid UPDATE syntax. Use: UPDATE tablename SET attr = constant [, attr = constant]* [WHERE condition];");
        }

        String tableName = tokens.get(1);

        // Parse SET clauses
        Map<String, String> setClauses = new HashMap<>();
        int i = 3;

        while (i < tokens.size() && !tokens.get(i).toUpperCase().equals("WHERE")) {
            String attr = tokens.get(i);
            if (i + 1 >= tokens.size() || !tokens.get(i + 1).equals("=")) {
                throw new Exception("Invalid SET syntax. Expected '=' after attribute");
            }
            i += 2; // Skip =
            if (i >= tokens.size()) {
                throw new Exception("Invalid SET syntax. Missing value");
            }
            String value = tokens.get(i);
            setClauses.put(attr, value);
            i++;

            // Check for comma
            if (i < tokens.size() && tokens.get(i).equals(",")) {
                i++;
            }
        }

        // Parse WHERE clause if present
        Condition whereCondition = null;
        if (i < tokens.size() && tokens.get(i).toUpperCase().equals("WHERE")) {
            i++;
            if (i + 2 >= tokens.size()) {
                throw new Exception("Invalid WHERE clause");
            }

            String leftAttr = tokens.get(i);
            String operator = tokens.get(i + 1);
            String right = tokens.get(i + 2);

            boolean isConstant = determineIfConstant(right);
            whereCondition = new Condition(leftAttr, operator, right, isConstant);
        }

        Database db = databases.get(currentDatabase);
        db.update(tableName, setClauses, whereCondition);
    }

    private static void parseDelete(List<String> tokens) throws Exception {
        if (currentDatabase == null) {
            throw new Exception("No database selected");
        }

        if (tokens.size() < 2) {
            throw new Exception("Invalid DELETE syntax. Use: DELETE tablename [WHERE condition];");
        }

        String tableName = tokens.get(1);
        int i = 2;

        Condition whereCondition = null;

        // Check for WHERE clause
        if (i < tokens.size() && tokens.get(i).toUpperCase().equals("WHERE")) {
            i++;
            if (i + 2 >= tokens.size()) {
                throw new Exception("Invalid WHERE clause");
            }

            String leftAttr = tokens.get(i);
            String operator = tokens.get(i + 1);
            String right = tokens.get(i + 2);

            boolean isConstant = determineIfConstant(right);
            whereCondition = new Condition(leftAttr, operator, right, isConstant);
        }

        Database db = databases.get(currentDatabase);
        db.deleteFrom(tableName, whereCondition);
    }

    private static void parseRename(List<String> tokens) throws Exception {
        if (currentDatabase == null) {
            throw new Exception("No database selected");
        }

        if (tokens.size() < 4 || !tokens.get(2).equals("(")) {
            throw new Exception("Invalid RENAME syntax. Use: RENAME tablename (attr1, attr2, ...);");
        }

        String tableName = tokens.get(1);

        // Parse new attribute names
        List<String> newAttrNames = new ArrayList<>();
        int i = 3;
        while (i < tokens.size() && !tokens.get(i).equals(")")) {
            if (!tokens.get(i).equals(",")) {
                newAttrNames.add(tokens.get(i));
            }
            i++;
        }

        Database db = databases.get(currentDatabase);
        db.renameTable(tableName, newAttrNames);
    }

    private static void parseLet(List<String> tokens) throws Exception {
        if (currentDatabase == null) {
            throw new Exception("No database selected");
        }

        if (tokens.size() < 5 || !tokens.get(2).toUpperCase().equals("KEY")) {
            throw new Exception("Invalid LET syntax. Use: LET tablename KEY attrname <SELECT OPERATION>;");
        }

        String newTableName = tokens.get(1).toLowerCase();
        String keyAttr = tokens.get(3).toLowerCase();

        // Find the SELECT operation (should start after KEY attrname)
        int selectIndex = 4;
        while (selectIndex < tokens.size() && !tokens.get(selectIndex).toUpperCase().equals("SELECT")) {
            selectIndex++;
        }

        if (selectIndex >= tokens.size()) {
            throw new Exception("Missing SELECT operation in LET command");
        }

        // Extract SELECT tokens
        List<String> selectTokens = tokens.subList(selectIndex, tokens.size());

        // Parse and execute the SELECT to get results
        Database db = databases.get(currentDatabase);

        // Parse the SELECT operation
        LetSelectResult result = parseLetSelect(selectTokens, db);

        // Create new table from results
        db.createTableFromSelect(newTableName, keyAttr, result);
    }

    private static LetSelectResult parseLetSelect(List<String> tokens, Database db) throws Exception {
        List<String> attrList = new ArrayList<>();
        int i = 1; // Skip SELECT token

        // Parse attribute list
        if (i < tokens.size() && tokens.get(i).equals("*")) {
            attrList.add("*");
            i++;
        } else {
            while (i < tokens.size() && !tokens.get(i).toUpperCase().equals("FROM")) {
                if (!tokens.get(i).equals(",")) {
                    attrList.add(tokens.get(i));
                }
                i++;
            }
        }

        if (i >= tokens.size() || !tokens.get(i).toUpperCase().equals("FROM")) {
            throw new Exception("Invalid SELECT syntax in LET command");
        }

        i++;

        if (i >= tokens.size()) {
            throw new Exception("Missing table name in LET command");
        }

        String tableName = tokens.get(i);
        i++;

        // Parse WHERE clause if present
        Condition whereCondition = null;
        if (i < tokens.size() && tokens.get(i).toUpperCase().equals("WHERE")) {
            i++;
            if (i + 2 >= tokens.size()) {
                throw new Exception("Invalid WHERE clause in LET command");
            }

            String leftAttr = tokens.get(i);
            String operator = tokens.get(i + 1);
            String right = tokens.get(i + 2);

            boolean isConstant = determineIfConstant(right);
            whereCondition = new Condition(leftAttr, operator, right, isConstant);
        }

        // Execute the SELECT
        Table table = db.getTable(tableName);
        List<Record> results = new ArrayList<>();
        Map<String, DataType> attributeTypes = new HashMap<>();

        // Determine which attributes to select
        List<String> displayAttrs;
        if (attrList.size() == 1 && attrList.get(0).equals("*")) {
            displayAttrs = new ArrayList<>();
            for (Attribute attr : table.getAttributes()) {
                displayAttrs.add(attr.getName());
                attributeTypes.put(attr.getName(), attr.getType());
            }
        } else {
            displayAttrs = attrList;
            for (String attrName : attrList) {
                Attribute attr = table.getAttribute(attrName);
                if (attr == null) {
                    throw new Exception("Unknown attribute: " + attrName);
                }
                attributeTypes.put(attrName, attr.getType());
            }
        }

        // Get records
        List<Long> recordPositions;
        if (whereCondition != null && whereCondition.isKeyCondition(table.getPrimaryKey())) {
            Object keyValue = whereCondition.getConstantValue();
            Attribute keyAttr = table.getAttribute(table.getPrimaryKey());
            Object parsedKey = keyAttr.parseValue(keyValue.toString());
            Long position = table.getIndex().search((Comparable) parsedKey);
            recordPositions = position != null ? Collections.singletonList(position) : new ArrayList<>();
        } else if (table.getPrimaryKey() != null && table.getIndex() != null) {
            recordPositions = table.getIndex().inOrderTraversal();
        } else {
            recordPositions = table.getAllRecordPositions();
        }

        for (Long pos : recordPositions) {
            Record record = table.readRecordAtPosition(pos);
            if (record != null) {
                if (whereCondition == null || whereCondition.evaluate(record, table.getAttributes())) {
                    results.add(record);
                }
            }
        }

        return new LetSelectResult(displayAttrs, results, attributeTypes);
    }

    private static void parseInput(List<String> tokens) throws Exception {
        if (tokens.size() < 2) {
            throw new Exception("Invalid INPUT syntax. Use: INPUT filename [OUTPUT filename];");
        }

        String inputFile = tokens.get(1);
        String outputFile = null;

        // Check for OUTPUT clause
        if (tokens.size() > 2 && tokens.get(2).toUpperCase().equals("OUTPUT")) {
            if (tokens.size() < 4) {
                throw new Exception("Missing output filename");
            }
            outputFile = tokens.get(3);
        }

        processInputFile(inputFile, outputFile);
    }

    private static void processInputFile(String inputFile, String outputFile) throws Exception {
        File file = new File(inputFile);
        if (!file.exists()) {
            throw new Exception("Input file not found: " + inputFile);
        }

        // Save current output stream if redirecting
        PrintStream originalOut = System.out;
        PrintStream fileOut = null;

        try {
            if (outputFile != null) {
                fileOut = new PrintStream(new FileOutputStream(outputFile));
                System.setOut(fileOut);
            }

            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                StringBuilder command = new StringBuilder();
                String line;

                System.out.println("Processing input file: " + inputFile);
                System.out.println("=====================================");

                while ((line = reader.readLine()) != null) {
                    // Skip empty lines and comments
                    if (line.trim().isEmpty() || line.trim().startsWith("--")) {
                        continue;
                    }

                    command.append(line).append(" ");

                    if (line.trim().endsWith(";")) {
                        // Execute the command
                        String cmd = command.toString().trim();
                        System.out.println("\n> " + cmd);

                        try {
                            processCommand(cmd);
                        } catch (Exception e) {
                            System.out.println("Error: " + e.getMessage());
                        }

                        command = new StringBuilder();
                    }
                }

                System.out.println("\n=====================================");
                System.out.println("Input file processing complete");
            }
        } finally {
            if (fileOut != null) {
                System.setOut(originalOut);
                fileOut.close();
            }
        }
    }

    private static boolean determineIfConstant(String token) {
        if (token.startsWith("\"") && token.endsWith("\"")) {
            return true;
        }
        if (token.matches("-?\\d+(\\.\\d+)?")) {
            return true;
        }
        if (token.toUpperCase().equals("TRUE") || token.toUpperCase().equals("FALSE")) {
            return true;
        }
        if (token.length() > 0 && (Character.isLetter(token.charAt(0)) || token.charAt(0) == '_')) {
            return false;
        }
        return true;
    }

    private static void parseUse(List<String> tokens) throws Exception {
        if (tokens.size() < 2) {
            throw new Exception("USE requires a database name");
        }

        String dbName = tokens.get(1);
        useDatabase(dbName);
    }

    private static void parseDescribe(List<String> tokens) throws Exception {
        if (tokens.size() < 2) {
            throw new Exception("DESCRIBE requires ALL or a table name");
        }

        if (currentDatabase == null) {
            throw new Exception("No database selected");
        }

        String target = tokens.get(1);
        Database db = databases.get(currentDatabase);

        if (target.toUpperCase().equals("ALL")) {
            db.describeAll();
        } else {
            db.describeTable(target);
        }
    }

    private static void createDatabase(String dbName) throws Exception {
        if (databases.containsKey(dbName)) {
            throw new Exception("Database " + dbName + " already exists");
        }

        Database db = new Database(dbName);
        databases.put(dbName, db);
        System.out.println("Database " + dbName + " created");
    }

    private static void useDatabase(String dbName) throws Exception {
        if (!databases.containsKey(dbName)) {
            Database db = Database.load(dbName);
            if (db == null) {
                throw new Exception("Database " + dbName + " does not exist");
            }
            databases.put(dbName, db);
        }

        if (currentDatabase != null) {
            databases.get(currentDatabase).close();
        }

        currentDatabase = dbName;
        System.out.println("Using database " + dbName);
    }
}
