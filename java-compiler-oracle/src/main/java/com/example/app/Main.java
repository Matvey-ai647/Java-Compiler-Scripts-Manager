import com.example.compiler.CompilerManager;
import com.example.database.OracleConnectionFactory;

public class Main {
    public static void main(String[] args) {
        // Initialize the compiler manager
        CompilerManager compilerManager = new CompilerManager();

        // Connect to the Oracle database
        try (OracleConnectionFactory connectionFactory = new OracleConnectionFactory()) {
            connectionFactory.connect();
            System.out.println("Connected to the Oracle database successfully.");
        } catch (Exception e) {
            System.err.println("Failed to connect to the Oracle database: " + e.getMessage());
        }

        // Additional logic for using the compiler can be added here
    }
}