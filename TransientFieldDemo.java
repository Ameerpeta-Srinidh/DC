import java.io.*;
public class TransientFieldDemo{
    public static void main(String[] args) throws Exception{
        UserSession session = new UserSession("admin_user","Secret_Token_123");
        ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream("session.ser"));
        oos.writeObject(session);
        oos.close();

        // Deserialize object
        ObjectInputStream ois = new ObjectInputStream(new FileInputStream("session.ser"));
        UserSession restoredSession = (UserSession) ois.readObject();
        ois.close();

        System.out.println("Username: " + restoredSession.username);
        
        // Step 2: State the expected output value for the transient field
        System.out.println("Token: " + restoredSession.sessionToken);
        // Expected Output for Token: ______________________
    }
}
