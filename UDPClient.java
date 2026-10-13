
import java.io.*;
import java.net.*;

public class UDPClient {
    private static final String SERVER_HOST = "localhost";
    private static final int SERVER_PORT = 9876;
    private static final int BUFFER_SIZE = 4096;

    public static void main(String[] args) {
        try (DatagramSocket clientSocket = new DatagramSocket()) {
            InetAddress serverAddress = InetAddress.getByName(SERVER_HOST);

            Student[] students = {
                new Student(101, "Alice", 84.5),
                new Student(102, "Bob", 95.0),
                new Student(103, "Charlie", 72.0),
                new Student(104, "Diana", 91.5)
            };

            // --- Step 1: Send Student array to Server ---
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ObjectOutputStream oos = new ObjectOutputStream(baos);
            oos.writeObject(students);
            oos.flush();

            byte[] sendData = baos.toByteArray();
            DatagramPacket sendPacket = new DatagramPacket(sendData, sendData.length, serverAddress, SERVER_PORT);
            clientSocket.send(sendPacket);
            System.out.println("Sent student list to server.");

            // --- Step 2: Receive best Student object ---
            byte[] receiveBuffer = new byte[BUFFER_SIZE];
            DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            clientSocket.receive(receivePacket);

            ByteArrayInputStream bais = new ByteArrayInputStream(receivePacket.getData());
            ObjectInputStream ois = new ObjectInputStream(bais);
            Student bestStudent = (Student) ois.readObject();
            System.out.println("Received best student: " + bestStudent);

            // --- Step 3: Set GPA parameters and send back ---
            bestStudent.setTotalPossibleMarks(100.0);
            bestStudent.setGpaScale(4.0);

            baos = new ByteArrayOutputStream();
            oos = new ObjectOutputStream(baos);
            oos.writeObject(bestStudent);
            oos.flush();

            sendData = baos.toByteArray();
            sendPacket = new DatagramPacket(sendData, sendData.length, serverAddress, SERVER_PORT);
            clientSocket.send(sendPacket);
            System.out.println("Sent updated student with GPA parameters back to server.");

            // --- Step 4: Receive calculated GPA ---
            receiveBuffer = new byte[BUFFER_SIZE];
            receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
            clientSocket.receive(receivePacket);

            bais = new ByteArrayInputStream(receivePacket.getData());
            ois = new ObjectInputStream(bais);
            double calculatedGpa = ois.readDouble();

            // --- Step 5: Report to console and exit ---
            System.out.println("\n================ BEST STUDENT REPORT ================");
            System.out.println(bestStudent);
            System.out.printf("GPA: %.2f / %.1f\n", calculatedGpa, bestStudent.getGpaScale());
            System.out.println("=====================================================");

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
