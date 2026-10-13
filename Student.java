
import java.io.Serializable;

public class Student implements Serializable {
    private static final long serialVersionUID = 1L;

    private int id;
    private String name;
    private double marks;
    private double totalPossibleMarks; // Parameter set by client for GPA computation
    private double gpaScale;            // e.g., 4.0 or 10.0

    public Student(int id, String name, double marks) {
        this.id = id;
        this.name = name;
        this.marks = marks;
    }

    public int getId() { return id; }
    public String getName() { return name; }
    public double getMarks() { return marks; }

    public double getTotalPossibleMarks() { return totalPossibleMarks; }
    public void setTotalPossibleMarks(double totalPossibleMarks) { 
        this.totalPossibleMarks = totalPossibleMarks; 
    }

    public double getGpaScale() { return gpaScale; }
    public void setGpaScale(double gpaScale) { 
        this.gpaScale = gpaScale; 
    }

    @Override
    public String toString() {
        return String.format("ID: %d | Name: %s | Marks: %.2f", id, name, marks);
    }
}
