# Java Compiler with Oracle Database Connection

This project is a Java-based compiler that connects to a local Oracle database. It provides a framework for compiling Java files and managing diagnostics, along with a sample application demonstrating its functionality.

## Project Structure

```
java-compiler-oracle
├── src
│   ├── main
│   │   ├── java
│   │   │   └── com
│   │   │       └── example
│   │   │           ├── app
│   │   │           │   └── Main.java
│   │   │           ├── compiler
│   │   │           │   ├── CompilerManager.java
│   │   │           │   └── diagnostics
│   │   │           │       └── DiagnosticPrinter.java
│   │   │           └── database
│   │   │               └── OracleConnectionFactory.java
│   │   └── resources
│   │       └── application.properties.example
│   └── test
│       └── java
│           └── com
│               └── example
│                   └── compiler
├── examples
│   └── HelloWorld.java
├── .gitignore
├── pom.xml
└── README.md
```

## Setup Instructions

1. **Clone the Repository**: 
   Clone this repository to your local machine using:
   ```
   git clone <repository-url>
   ```

2. **Install Dependencies**: 
   Navigate to the project directory and run:
   ```
   mvn install
   ```
   This will download all necessary dependencies specified in the `pom.xml` file.

3. **Configure Database Connection**: 
   Update the `src/main/resources/application.properties.example` file with your Oracle database connection details.

4. **Run the Application**: 
   You can run the application using the following command:
   ```
   mvn exec:java -Dexec.mainClass="com.example.app.Main"
   ```

## Usage

- To compile Java files, use the `CompilerManager` class.
- Diagnostic messages will be printed using the `DiagnosticPrinter` class.
- The `OracleConnectionFactory` class manages connections to the Oracle database.

## Example

The `examples/HelloWorld.java` file contains a simple Java program that can be compiled and executed to demonstrate the compiler's functionality.

## Contributing

Contributions are welcome! Please submit a pull request or open an issue for any enhancements or bug fixes.

## License

This project is licensed under the MIT License. See the LICENSE file for details.