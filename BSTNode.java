import java.io.Serializable;

class BSTNode implements Serializable {
    private static final long serialVersionUID = 1L;
    Comparable key;
    long recordPosition;  // Position in the data file (points to record start marker)
    BSTNode left, right;

    public BSTNode(Comparable key, long recordPosition) {
        this.key = key;
        this.recordPosition = recordPosition;
        left = right = null;
    }
}
