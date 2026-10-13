import java.io.*;

class UserSession implements Serializable{
    String username;
    transient String sessionToken;

    public UserSession(String username, String sessionToken){
        this.username = username;
        this.sessionToken = sessionToken;
    }

    
}