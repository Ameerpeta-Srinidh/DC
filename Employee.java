import java.io.*;

class Employee implements Serializable{
    String name;
    double salary;
    
    public Employee(String name, double salary){
        this.name = name;
        this.salary = salary;
    }
}