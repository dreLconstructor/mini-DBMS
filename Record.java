import java.io.Serializable;
import java.util.*;

class Record implements Serializable {
    private static final long serialVersionUID = 1L;
    private Map<String, Object> values;

    public Record() {
        values = new HashMap<>();
    }

    public void setValue(String attribute, Object value) {
        values.put(attribute.toLowerCase(), value);
    }

    public Object getValue(String attribute) {
        return values.get(attribute.toLowerCase());
    }

    public Map<String, Object> getValues() {
        return values;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Object value : values.values()) {
            if (!first) sb.append(", ");
            first = false;
            if (value instanceof String) {
                sb.append("\"").append(value).append("\"");
            } else {
                sb.append(value);
            }
        }
        return sb.toString();
    }
}
