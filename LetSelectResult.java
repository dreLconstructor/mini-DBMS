import java.util.*;

class LetSelectResult {
    List<String> attributeNames;
    List<Record> records;
    Map<String, DataType> attributeTypes;

    public LetSelectResult(List<String> attributeNames, List<Record> records, Map<String, DataType> attributeTypes) {
        this.attributeNames = attributeNames;
        this.records = records;
        this.attributeTypes = attributeTypes;
    }
}
