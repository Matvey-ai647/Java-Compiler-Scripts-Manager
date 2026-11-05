import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

public class OracleConnectionFactory {
    private static final String URL = "jdbc:oracle:thin:@localhost:1521:ORCL"; // Update with your database URL
    private static final String USER = "sys"; // Update with your database username
    private static final String PASSWORD = "1234"; // Update with your database password

    public static Connection getConnection() throws SQLException {
        java.util.Properties info = new java.util.Properties();
        info.put("user", USER);
        info.put("password", PASSWORD);
        info.put("internal_logon", "sysdba");
        return DriverManager.getConnection(URL, info);
    }

    public static void closeConnection(Connection connection) {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                e.printStackTrace();
            }
        }
    }
}