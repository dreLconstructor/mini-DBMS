import java.io.Serializable;

class Attribute implements Serializable {
    private static final long serialVersionUID = 1L;
    private String name;
    private DataType type;
    private boolean isPrimaryKey;

    public Attribute(String name, DataType type, boolean isPrimaryKey) {
        this.name = name.toLowerCase();
        this.type = type;
        this.isPrimaryKey = isPrimaryKey;
    }

    public String getName() {
        return name;
    }

    public DataType getType() {
        return type;
    }

    public boolean isPrimaryKey() {
        return isPrimaryKey;
    }

    public Object parseValue(String value) throws Exception {
        value = value.trim();

        // Remove quotes for string constants
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
            if (type == DataType.TEXT) {
                return value;
            } else {
                throw new Exception("String value not allowed for " + type);
            }
        }

        switch (type) {
            case INTEGER:
                try {
                    return Integer.parseInt(value);
                } catch (NumberFormatException e) {
                    throw new Exception("Invalid integer: " + value);
                }
            case FLOAT:
                try {
                    return Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    throw new Exception("Invalid float: " + value);
                }
            case TEXT:
                return value;
            default:
                throw new Exception("Unknown data type");
        }
    }

    @Override
    public String toString() {
        return name + " " + type;
    }
}
