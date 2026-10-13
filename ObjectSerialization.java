
import java.io.*;

class ObjectSerialization{
    public static void main(String[] args) throws Exception{
        Employee emp = new Employee("Rajesh",75000.0);
        FileOutputStream fos = new FileOutputStream("employee.ser");
        ObjectOutputStream oos = new ObjectOutputStream(fos);
        oos.writeObject(emp);
        oos.close();

        FileInputStream fis = new FileInputStream("employee.ser");
        ObjectInputStream ois = new ObjectInputStream(fis);
        Employee restoredEmp = (Employee) ois.readObject();
        ois.close();

        System.out.println("Employee Name: " + restoredEmp.name + " Salary:" + restoredEmp.salary);

    }
}