import java.io.*;

public class PrimitiveSerialization {
    public static void main(String[] args) throws Exception {
        // Step 1: Complete stream setup for writing primitives
        FileOutputStream fos = new FileOutputStream("data.bin");
        DataOutputStream dos = new DataOutputStream(fos);

        // Step 2: Fill in methods to write primitive values
        dos.writeInt(101);             // Write an integer
        dos.writeDouble(98.5);            // Write a double
        dos.writeBoolean(true);            // Write a boolean
        dos.close();

        // Step 3: Complete stream setup for reading primitives
        FileInputStream fis = new FileInputStream("data.bin");
        DataInputStream dis = new DataInputStream(fis);

        
        int id = dis.readInt();
        double score = dis.readDouble();
        boolean status = dis.readBoolean();
        dis.close();

        System.out.println("ID: " + id + ", Score: " + score + ", Status: " + status);
    }
}
