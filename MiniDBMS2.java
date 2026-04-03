import java.io.*;
import java.util.*;

public class MiniDBMS2 {
    private static Scanner scanner = new Scanner(System.in);
    private static String currentDatabase = null;
    private static Map<String, Database> databases = new HashMap<>();
    // [CHANGED] Leftover characters after the last ";" on a line are kept here
    // so the next call to readMultilineInput() picks them up before reading more input.
    private static StringBuilder inputBuffer = new StringBuilder();

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
        // [CHANGED] Rewritten to handle multiple semicolon-separated commands on one line.
        // inputBuffer accumulates characters read from lines; we return one command at a time
        // (everything up to and including the next ";"), leaving the rest in the buffer.
        while (true) {
            // Check if the buffer already contains a complete command (has a ";")
            int semiIdx = inputBuffer.indexOf(";");
            if (semiIdx >= 0) {
                String command = inputBuffer.substring(0, semiIdx + 1).trim();
                inputBuffer.delete(0, semiIdx + 1);
                if (!command.isEmpty()) {
                    return command;
                }
                // Empty segment (e.g. double ";;"), keep scanning
                continue;
            }

            // Need more input
            if (!scanner.hasNextLine()) {
                // EOF — return whatever is left if non-empty, else null
                String remaining = inputBuffer.toString().trim();
                inputBuffer.setLength(0);
                return remaining.isEmpty() ? null : remaining;
            }

            String line = scanner.nextLine();
            // Append with a space separator so tokens across lines merge correctly
            if (inputBuffer.length() > 0) {
                inputBuffer.append(" ");
            }
            inputBuffer.append(line);
        }
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

    // [CHANGED] SQL keywords ordered longest-first so longer matches take priority
    // (e.g. "DELETE" is checked before "LET", "INSERT" before "SET").
    private static final String[] SQL_KEYWORDS = {
        "DATABASE", "DESCRIBE", "AVERAGE", "INTEGER", "PRIMARY",
        "SELECT", "INSERT", "UPDATE", "DELETE", "RENAME", "CREATE", "VALUES",
        "FLOAT", "WHERE", "TABLE", "COUNT",
        "FROM", "TEXT", "INTO",
        "KEY", "SET", "USE", "LET", "AVG", "MIN", "MAX", "AND",
        "OR"
    };

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

        // [CHANGED] Split any tokens that have keywords fused to identifiers due to missing spaces
        // e.g. "deletestudent" -> ["delete","student"], "atext" -> ["a","text"], "orb" -> ["or","b"]
        List<String> result = new ArrayList<>();
        for (String token : tokens) {
            splitFusedToken(token, result);
        }
        return result;
    }

    // [CHANGED] Recursively splits a token that begins or ends with a SQL keyword.
    // Checks starts-with first (handles "deletestudent", "orb"), then ends-with
    // (handles "atext", "zfrom", "studentwhere"). Recurses so multi-fused tokens
    // like "deletestudent" also get their remainder checked.
    private static void splitFusedToken(String token, List<String> out) {
        // Never split: quoted strings, punctuation/operators, or single-char tokens
        if (token.startsWith("\"") || token.length() <= 1 ||
                token.equals("(") || token.equals(")") || token.equals(",") ||
                token.equals("=") || token.equals("!=") || token.equals("<=") ||
                token.equals(">=") || token.equals("<") || token.equals(">")) {
            out.add(token);
            return;
        }

        String upper = token.toUpperCase();

        // Check if token STARTS WITH a keyword followed by more characters
        for (String kw : SQL_KEYWORDS) {
            if (upper.startsWith(kw) && upper.length() > kw.length()) {
                splitFusedToken(token.substring(0, kw.length()), out);
                splitFusedToken(token.substring(kw.length()), out);
                return;
            }
        }

        // Check if token ENDS WITH a keyword preceded by more characters
        for (String kw : SQL_KEYWORDS) {
            if (upper.endsWith(kw) && upper.length() > kw.length()) {
                splitFusedToken(token.substring(0, token.length() - kw.length()), out);
                splitFusedToken(token.substring(token.length() - kw.length()), out);
                return;
            }
        }

        // No keyword found — emit as-is
        out.add(token);
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

        // [CHANGED] "INTO" is now optional — rubric specifies INSERT TableName VALUES (...)
        // but we also still accept INSERT INTO TableName VALUES (...) for compatibility.
        String tableName;
        int tableIdx;
        if (tokens.size() > 1 && tokens.get(1).toUpperCase().equals("INTO")) {
            if (tokens.size() < 3) {
                throw new Exception("Invalid INSERT syntax. Missing table name after INTO.");
            }
            tableName = tokens.get(2);
            tableIdx = 2;
        } else {
            if (tokens.size() < 2) {
                throw new Exception("Invalid INSERT syntax. Use: INSERT TableName VALUES (...);");
            }
            tableName = tokens.get(1);
            tableIdx = 1;
        }

        int valuesIndex = -1;
        for (int i = tableIdx + 1; i < tokens.size(); i++) {
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
            // [CHANGED] Added "AVERAGE" as an alias alongside "AVG"
            if (firstToken.equals("COUNT") || firstToken.equals("MIN") ||
                    firstToken.equals("MAX") || firstToken.equals("AVG") ||
                    firstToken.equals("AVERAGE")) {
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

        // [CHANGED] Collect multiple comma-separated table names for cross-join support
        List<String> tableNames = new ArrayList<>();
        tableNames.add(tokens.get(i));
        i++;
        while (i < tokens.size() && tokens.get(i).equals(",")) {
            i++; // skip comma
            if (i >= tokens.size()) {
                throw new Exception("Expected table name after ',' in FROM clause.");
            }
            tableNames.add(tokens.get(i));
            i++;
        }

        // [CHANGED] Parse up to 3 conditions joined by AND/OR
        Condition whereCondition = null;
        if (i < tokens.size() && tokens.get(i).toUpperCase().equals("WHERE")) {
            i++;
            whereCondition = parseWhereConditions(tokens, i);
        }

        Database db = databases.get(currentDatabase);
        db.select(tableNames, attrList, whereCondition);
    }

    private static void parseAggregateSelect(List<String> tokens) throws Exception {
        String function = tokens.get(1).toUpperCase();
        String attribute;
        int i = 2;

        // [CHANGED] The tokenizer always splits "(" into its own token, so
        // "avg(gpa)" becomes ["avg", "(", "gpa", ")"] — tokens.get(2) is always "(", never "(gpa)".
        // We now skip the "(" token, read the attribute at tokens.get(3), then skip ")".
        if (i < tokens.size() && tokens.get(i).equals("(")) {
            i++; // skip "("
            if (i >= tokens.size()) throw new Exception("Invalid aggregate syntax: missing attribute");
            attribute = tokens.get(i); // "gpa" or "*"
            i++; // skip attribute
            if (i < tokens.size() && tokens.get(i).equals(")")) {
                i++; // skip ")"
            }
        } else if (i < tokens.size() && tokens.get(i).startsWith("(")) {
            // Fallback for compact token like "(gpa)" (shouldn't occur with this tokenizer, but kept for safety)
            attribute = tokens.get(i).substring(1);
            if (attribute.endsWith(")")) attribute = attribute.substring(0, attribute.length() - 1);
            i++;
        } else {
            // No parentheses at all
            attribute = tokens.get(i);
            i++;
            if (i < tokens.size() && tokens.get(i).equals(")")) i++;
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

        // [CHANGED] Parse WHERE clause with AND/OR compound support
        Condition whereCondition = null;
        if (i < tokens.size() && tokens.get(i).toUpperCase().equals("WHERE")) {
            i++;
            whereCondition = parseWhereConditions(tokens, i);
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

        // [CHANGED] Parse WHERE clause with AND/OR compound support
        Condition whereCondition = null;
        if (i < tokens.size() && tokens.get(i).toUpperCase().equals("WHERE")) {
            i++;
            whereCondition = parseWhereConditions(tokens, i);
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

        // [CHANGED] Parse WHERE clause with AND/OR compound support
        Condition whereCondition = null;
        if (i < tokens.size() && tokens.get(i).toUpperCase().equals("WHERE")) {
            i++;
            whereCondition = parseWhereConditions(tokens, i);
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

        // [CHANGED] Collect multiple comma-separated table names (mirrors parseSelect)
        List<String> tableNames = new ArrayList<>();
        tableNames.add(tokens.get(i));
        i++;
        while (i < tokens.size() && tokens.get(i).equals(",")) {
            i++; // skip comma
            if (i >= tokens.size()) throw new Exception("Expected table name after ',' in FROM clause.");
            tableNames.add(tokens.get(i));
            i++;
        }

        // [CHANGED] Parse WHERE clause with AND/OR compound support
        Condition whereCondition = null;
        if (i < tokens.size() && tokens.get(i).toUpperCase().equals("WHERE")) {
            i++;
            whereCondition = parseWhereConditions(tokens, i);
        }

        // Build combined attribute list from all tables
        List<Table> tableList = new ArrayList<>();
        List<Attribute> combinedAttrs = new ArrayList<>();
        for (String tn : tableNames) {
            Table t = db.getTable(tn);
            tableList.add(t);
            combinedAttrs.addAll(t.getAttributes());
        }

        // Compute Cartesian product of all tables' records
        // [CHANGED] Use getOrderedRecordPositions() so PK tables use BST in-order traversal
        List<Record> crossProduct = new ArrayList<>();
        crossProduct.add(new Record());
        for (Table t : tableList) {
            List<Long> positions = t.getOrderedRecordPositions();
            List<Record> tableRecords = new ArrayList<>();
            for (Long pos : positions) {
                Record r = t.readRecordAtPosition(pos);
                if (r != null) tableRecords.add(r);
            }
            List<Record> newProduct = new ArrayList<>();
            for (Record existing : crossProduct) {
                for (Record r : tableRecords) {
                    Record merged = new Record();
                    for (Map.Entry<String, Object> e : existing.getValues().entrySet())
                        merged.setValue(e.getKey(), e.getValue());
                    for (Map.Entry<String, Object> e : r.getValues().entrySet())
                        merged.setValue(e.getKey(), e.getValue());
                    newProduct.add(merged);
                }
            }
            crossProduct = newProduct;
        }

        // Filter by WHERE, then build result
        List<Record> results = new ArrayList<>();
        for (Record record : crossProduct) {
            if (whereCondition == null || whereCondition.evaluate(record, combinedAttrs)) {
                results.add(record);
            }
        }

        // Resolve attribute types and display attribute list
        Map<String, DataType> attributeTypes = new HashMap<>();
        for (Attribute attr : combinedAttrs) {
            attributeTypes.put(attr.getName(), attr.getType());
        }

        List<String> displayAttrs;
        if (attrList.size() == 1 && attrList.get(0).equals("*")) {
            displayAttrs = new ArrayList<>();
            for (Attribute attr : combinedAttrs) {
                displayAttrs.add(attr.getName());
            }
        } else {
            displayAttrs = attrList;
            for (String attrName : attrList) {
                boolean found = false;
                for (Attribute attr : combinedAttrs) {
                    if (attr.getName().equalsIgnoreCase(attrName)) { found = true; break; }
                }
                if (!found) throw new Exception("Unknown attribute: " + attrName);
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
                System.out.println("Processing input file: " + inputFile);
                System.out.println("=====================================");

                // [CHANGED] Use a buffer that accumulates all file content so we can split
                // correctly on ";" boundaries regardless of how many commands share a line.
                StringBuilder buffer = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.trim().isEmpty() || line.trim().startsWith("--")) continue;
                    if (buffer.length() > 0) buffer.append(" ");
                    buffer.append(line);
                }

                // Split on ";" and execute each command in order
                while (buffer.length() > 0) {
                    int semi = buffer.indexOf(";");
                    if (semi < 0) break; // no more complete commands
                    String cmd = buffer.substring(0, semi + 1).trim();
                    buffer.delete(0, semi + 1);
                    if (cmd.isEmpty() || cmd.equals(";")) continue;

                    System.out.println("\n> " + cmd);
                    try {
                        processCommand(cmd);
                    } catch (Exception e) {
                        System.out.println("Error: " + e.getMessage());
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

    // [CHANGED] Helper: parses WHERE conditions starting at tokens[startIdx].
    // Supports up to 3 conditions joined by AND/OR and returns a (possibly compound) Condition.
    private static Condition parseWhereConditions(List<String> tokens, int startIdx) throws Exception {
        if (startIdx + 2 >= tokens.size()) {
            throw new Exception("Invalid WHERE clause");
        }
        List<Condition> conditions = new ArrayList<>();
        List<String> logicalOps = new ArrayList<>();

        // First condition
        conditions.add(new Condition(
                tokens.get(startIdx),
                tokens.get(startIdx + 1),
                tokens.get(startIdx + 2),
                determineIfConstant(tokens.get(startIdx + 2))));
        int i = startIdx + 3;

        // Additional conditions joined by AND / OR
        while (i < tokens.size() &&
                (tokens.get(i).toUpperCase().equals("AND") || tokens.get(i).toUpperCase().equals("OR"))) {
            logicalOps.add(tokens.get(i).toUpperCase());
            i++;
            if (i + 2 >= tokens.size()) {
                throw new Exception("Invalid WHERE clause after " + logicalOps.get(logicalOps.size() - 1));
            }
            conditions.add(new Condition(
                    tokens.get(i),
                    tokens.get(i + 1),
                    tokens.get(i + 2),
                    determineIfConstant(tokens.get(i + 2))));
            i += 3;
        }

        return conditions.size() == 1 ? conditions.get(0) : new Condition(conditions, logicalOps);
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
