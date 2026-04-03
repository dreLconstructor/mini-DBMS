import java.io.Serializable;
import java.util.*;

class BSTIndex implements Serializable {
    private static final long serialVersionUID = 1L;
    private BSTNode root;
    private String tableName;
    private String keyAttribute;
    private int size;

    public BSTIndex(String tableName, String keyAttribute) {
        this.tableName = tableName;
        this.keyAttribute = keyAttribute;
        this.root = null;
        this.size = 0;
    }

    public void insert(Comparable key, long recordPosition) {
        root = insertRec(root, key, recordPosition);
        size++;
    }

    private BSTNode insertRec(BSTNode root, Comparable key, long recordPosition) {
        if (root == null) {
            return new BSTNode(key, recordPosition);
        }

        int cmp = compareKeys(key, root.key);
        if (cmp < 0) {
            root.left = insertRec(root.left, key, recordPosition);
        } else if (cmp > 0) {
            root.right = insertRec(root.right, key, recordPosition);
        } else {
            // Duplicate key - update position
            root.recordPosition = recordPosition;
        }

        return root;
    }

    public Long search(Comparable key) {
        return searchRec(root, key);
    }

    private Long searchRec(BSTNode root, Comparable key) {
        if (root == null) {
            return null;
        }

        int cmp = compareKeys(key, root.key);
        if (cmp == 0) {
            return root.recordPosition;
        } else if (cmp < 0) {
            return searchRec(root.left, key);
        } else {
            return searchRec(root.right, key);
        }
    }

    public List<Long> inOrderTraversal() {
        List<Long> positions = new ArrayList<>();
        inOrderRec(root, positions);
        return positions;
    }

    private void inOrderRec(BSTNode root, List<Long> positions) {
        if (root != null) {
            inOrderRec(root.left, positions);
            positions.add(root.recordPosition);
            inOrderRec(root.right, positions);
        }
    }

    @SuppressWarnings("unchecked")
    private int compareKeys(Comparable key1, Comparable key2) {
        if (key1 instanceof String && key2 instanceof String) {
            return ((String) key1).compareToIgnoreCase((String) key2);
        }
        return key1.compareTo(key2);
    }

    public int getSize() {
        return size;
    }
}
