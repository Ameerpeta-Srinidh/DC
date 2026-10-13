import java.io.*;
import java.net.*;

public class UDPServer{
    private static final int PORT = 9876;
    private static final int BUFFER_SIZE = 4096;

    public static void main(String[] args){
        try (DatagramSocket serverSocket = new DatagramSocket(PORT)){
            System.out.println("UDP iterative server listening on port " + PORT + "...");

            while(true){
                try{
                    byte[] receiveBuffer = new byte[BUFFER_SIZE];
                    DatagramPacket receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    serverSocket.receive(receivePacket);

                    InetAddress clientAddress = receivePacket.getAddress();
                    int clientPort = receivePacket.getPort();
                    serverSocket.connect(clientAddress, clientPort);

                    serverSocket.connect(clientAddress,clientPort);
                    System.out.println("\nConnected to client: " + clientAddress + ":" + clientPort);

                    ByteArrayInputStream bais = new ByteArrayInputStream(receivePacket.getData(),receivePacket.getOffset(),receivePacket.getLength());
                    ObjectInputStream ois = new ObjectInputStream(bais);
                    Student[] students = (Student[]) ois.readObject();

                    Student bestStudent = students[0];
                    for(Student s : students){
                        if(s.getMarks() > bestStudent.getMarks()){
                            bestStudent = s;
                        }
                    }

                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ObjectOutputStream oos = new ObjectOutputStream(baos);
                    oos.writeObject(bestStudent);
                    oos.flush();

                    byte[] sendData = baos.toByteArray();
                    DatagramPacket sendPacket = new DatagramPacket(sendData,sendData.length,clientAddress,clientPort);
                    serverSocket.send(sendPacket);

                    receiveBuffer = new byte[BUFFER_SIZE];
                    receivePacket = new DatagramPacket(receiveBuffer, receiveBuffer.length);
                    serverSocket.receive(receivePacket);

                    bais = new ByteArrayInputStream(receivePacket.getData(),receivePacket.getOffset(),receivePacket.getLength());
                    ois = new ObjectInputStream(bais);
                    Student studentForGpa = (Student) ois.readObject();

                    double gpa = (studentForGpa.getMarks() / studentForGpa.getTotalPossibleMarks()) * studentForGpa.getGpaScale();

                    baos = new ByteArrayOutputStream();
                    oos = new ObjectOutputStream(baos);
                    oos.writeDouble(gpa);
                    oos.flush();

                    sendData = baos.toByteArray();
                    sendPacket = new DatagramPacket(sendData,sendData.length,clientAddress,clientPort);
                    serverSocket.send(sendPacket);

                    System.out.println("Completed transaction for client.");
                }catch(Exception e){
                    e.printStackTrace();
                }finally{
                    if(serverSocket.isConnected()){
                        serverSocket.disconnect();
                    }
                }
            }
        }catch(Exception e){
            e.printStackTrace();
        }
    }
}
