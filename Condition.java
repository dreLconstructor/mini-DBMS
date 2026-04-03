import java.util.List;
import java.util.ArrayList;

class Condition {
    // [CHANGED] Added isLeaf flag to distinguish leaf (single comparison) from compound conditions
    private boolean isLeaf;

    // Leaf condition fields (single comparison: attr op value)
    private String leftAttr;
    private String operator;
    private String rightValue;
    private boolean isConstant;

    // [CHANGED] Compound condition fields for AND/OR chains
    private List<Condition> subConditions;   // the individual comparisons
    private List<String> logicalOperators;   // "AND" or "OR", length = subConditions.size() - 1

    // Constructor for a single comparison condition (leaf node) — unchanged signature
    public Condition(String leftAttr, String operator, String right, boolean isConstant) {
        this.isLeaf = true;
        this.leftAttr = leftAttr.toLowerCase();
        this.operator = operator;
        this.rightValue = right;
        this.isConstant = isConstant;
    }

    // [CHANGED] Constructor for compound AND/OR conditions
    public Condition(List<Condition> subConditions, List<String> logicalOperators) {
        this.isLeaf = false;
        this.subConditions = subConditions;
        this.logicalOperators = logicalOperators;
    }

    public boolean evaluate(Record record, List<Attribute> attributes) throws Exception {
        if (isLeaf) {
            // Single comparison
            // [CHANGED] Validate that the left attribute actually exists in the schema.
            // Previously a missing attribute silently returned false (hiding errors).
            Attribute leftAttrObj = null;
            for (Attribute attr : attributes) {
                if (attr.getName().equals(leftAttr)) {
                    leftAttrObj = attr;
                    break;
                }
            }
            if (leftAttrObj == null) throw new Exception("Unknown attribute in WHERE clause: " + leftAttr);

            Object leftVal = record.getValue(leftAttr);
            if (leftVal == null) return false;

            Object rightVal;
            if (isConstant) {
                // For string constants, remove quotes if present
                String constValue = rightValue;
                if (constValue.startsWith("\"") && constValue.endsWith("\"")) {
                    constValue = constValue.substring(1, constValue.length() - 1);
                }
                // Parse the constant according to the attribute type
                rightVal = leftAttrObj.parseValue(constValue);
            } else {
                // Validate right-side attribute exists too
                String rightAttr = rightValue.toLowerCase();
                boolean rightFound = false;
                for (Attribute attr : attributes) {
                    if (attr.getName().equals(rightAttr)) { rightFound = true; break; }
                }
                if (!rightFound) throw new Exception("Unknown attribute in WHERE clause: " + rightValue);
                rightVal = record.getValue(rightAttr);
                if (rightVal == null) return false;
            }

            return compareValues(leftVal, rightVal, operator);
        } else {
            // [CHANGED] Compound evaluation: chain sub-conditions with AND/OR
            boolean result = subConditions.get(0).evaluate(record, attributes);
            for (int i = 0; i < logicalOperators.size(); i++) {
                boolean next = subConditions.get(i + 1).evaluate(record, attributes);
                if (logicalOperators.get(i).equals("AND")) {
                    result = result && next;
                } else { // OR
                    result = result || next;
                }
            }
            return result;
        }
    }

    @SuppressWarnings("unchecked")
    private boolean compareValues(Object left, Object right, String op) {
        // Handle string comparisons case-insensitively
        if (left instanceof String && right instanceof String) {
            String leftStr = (String) left;
            String rightStr = (String) right;
            int cmp = leftStr.compareToIgnoreCase(rightStr);

            switch (op) {
                case "=": return cmp == 0;
                case "!=": return cmp != 0;
                case "<": return cmp < 0;
                case ">": return cmp > 0;
                case "<=": return cmp <= 0;
                case ">=": return cmp >= 0;
                default: return false;
            }
        }

        // Handle numeric comparisons
        if (left instanceof Number && right instanceof Number) {
            double leftNum = ((Number) left).doubleValue();
            double rightNum = ((Number) right).doubleValue();

            switch (op) {
                case "=": return Math.abs(leftNum - rightNum) < 0.000001;
                case "!=": return Math.abs(leftNum - rightNum) >= 0.000001;
                case "<": return leftNum < rightNum;
                case ">": return leftNum > rightNum;
                case "<=": return leftNum <= rightNum;
                case ">=": return leftNum >= rightNum;
                default: return false;
            }
        }

        return false;
    }

    public boolean isKeyCondition(String primaryKey) {
        // [CHANGED] Compound conditions are never single-key conditions
        if (!isLeaf) return false;
        return primaryKey != null &&
                leftAttr.equals(primaryKey) &&
                operator.equals("=") &&
                isConstant;
    }

    public Object getConstantValue() throws Exception {
        // [CHANGED] Return null for compound conditions
        if (!isLeaf || !isConstant) return null;
        String constValue = rightValue;
        if (constValue.startsWith("\"") && constValue.endsWith("\"")) {
            constValue = constValue.substring(1, constValue.length() - 1);
        }
        return constValue;
    }
}
