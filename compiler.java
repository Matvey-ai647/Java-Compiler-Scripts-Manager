import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreeScanner;
import com.sun.source.util.Trees;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A small Java source compiler and diagnostics tool.
 *
 * Build with: javac compiler.java
 * Run with:   java compiler
 */
public class compiler {
    private static final String VERSION = "1.0";
    private static final Path ANALYZER_RESULTS = Paths.get("compiler-analyzer-results.txt");
    private static final Path DEBUG_RESULTS = Paths.get("compiler-debug-results.txt");
    private static boolean connected = true;

    public static void main(String[] args) {
        if (args.length == 0) {
            runShell();
            return;
        }

        int exitCode;
        try {
            exitCode = runCommand(args);
        } catch (IOException e) {
            System.err.println("Ошибка ввода-вывода: " + e.getMessage());
            exitCode = 2;
        } catch (IllegalArgumentException e) {
            System.err.println("Ошибка: " + e.getMessage());
            exitCode = 2;
        }
        if (exitCode != 0) {
            System.exit(exitCode);
        }
    }

    private static void runShell() {
        Scanner scanner = new Scanner(System.in);
        System.out.println("Java Compiler Scripts Manager " + VERSION);
        System.out.println("Введите help для списка команд, exit для выхода.");

        while (true) {
            System.out.print(connected ? "java> " : "java(disconnected)> ");
            if (!scanner.hasNextLine()) {
                return;
            }
            String line = scanner.nextLine().trim();
            if (line.isEmpty()) {
                continue;
            }

            String[] args = splitCommand(line);
            if ("exit".equalsIgnoreCase(args[0]) || "quit".equalsIgnoreCase(args[0])) {
                return;
            }
            try {
                runCommand(args);
            } catch (IOException e) {
                System.out.println("Ошибка ввода-вывода: " + e.getMessage());
            } catch (IllegalArgumentException e) {
                System.out.println("Ошибка: " + e.getMessage());
            }
        }
    }

    private static String[] splitCommand(String line) {
        List<String> parts = new ArrayList<String>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if ((c == '"' || c == '\'') && (!quoted || c == quote)) {
                if (quoted) {
                    quoted = false;
                } else {
                    quoted = true;
                    quote = c;
                }
            } else if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    parts.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (quoted) {
            throw new IllegalArgumentException("не закрыты кавычки в команде.");
        }
        if (current.length() > 0) {
            parts.add(current.toString());
        }
        return parts.toArray(new String[parts.size()]);
    }

    private static int runCommand(String[] args) throws IOException {
        String command = args[0].toLowerCase(Locale.ROOT);
        if ("help".equals(command) || "--help".equals(command) || "-h".equals(command)) {
            printHelp();
            return 0;
        }
        if ("connect".equals(command)) {
            connected = true;
            System.out.println("Компилятор подключен.");
            return 0;
        }
        if ("disconnect".equals(command)) {
            connected = false;
            System.out.println("Компилятор отключен.");
            return 0;
        }
        if ("status".equals(command)) {
            System.out.println("Состояние: " + (connected ? "подключен" : "отключен"));
            System.out.println("Версия: " + VERSION);
            return 0;
        }
        if ("packages".equals(command)) {
            return managePackages(args);
        }
        if ("results".equals(command) || "analyzer-results".equals(command)) {
            return showSavedResults(ANALYZER_RESULTS, "результаты анализаторов");
        }
        if ("debug-results".equals(command)) {
            return showSavedResults(DEBUG_RESULTS, "результаты Debug-отладки");
        }
        if ("analyzers".equals(command)) {
            if (!connected) {
                throw new IllegalArgumentException("компилятор отключен. Сначала выполните connect.");
            }
            if (args.length != 2) {
                throw new IllegalArgumentException("использование: analyzers <файл.java>");
            }
            return runTechnicalAnalyzers(validateSource(args[1]));
        }
        if ("debug".equals(command)) {
            if (!connected) {
                throw new IllegalArgumentException("компилятор отключен. Сначала выполните connect.");
            }
            if (args.length < 2) {
                throw new IllegalArgumentException("использование: debug <файл.java> [-- <аргументы программы>]");
            }
            int separator = -1;
            for (int i = 2; i < args.length; i++) {
                if ("--".equals(args[i])) {
                    separator = i;
                    break;
                }
            }
            for (int i = 2; i < (separator < 0 ? args.length : separator); i++) {
                throw new IllegalArgumentException("аргументы запуска указываются после --.");
            }
            return runDebug(validateSource(args[1]),
                    separator < 0 ? new String[0] : Arrays.copyOfRange(args, separator + 1, args.length));
        }
        if (!"analyze".equals(command) && !"tree".equals(command)
                && !"compile".equals(command) && !"fix".equals(command)) {
            throw new IllegalArgumentException("неизвестная команда '" + args[0] + "'. Используйте help.");
        }
        if (!connected) {
            throw new IllegalArgumentException("компилятор отключен. Сначала выполните connect.");
        }
        if (args.length < 2) {
            throw new IllegalArgumentException("укажите путь к Java-файлу.");
        }

        Path source = validateSource(args[1]);
        if ("fix".equals(command)) {
            formatSource(source);
            System.out.println("Безопасное форматирование применено: удалены пробелы в конце строк и добавлен финальный перевод строки.");
            return analyze(source, true, null);
        }
        if ("tree".equals(command)) {
            return analyze(source, true, null);
        }
        if ("compile".equals(command)) {
            Path outputDirectory = Paths.get("out").toAbsolutePath().normalize();
            for (int i = 2; i < args.length; i++) {
                if ("--out".equals(args[i]) && i + 1 < args.length) {
                    outputDirectory = Paths.get(args[++i]).toAbsolutePath().normalize();
                } else {
                    throw new IllegalArgumentException("неизвестный параметр: " + args[i]);
                }
            }
            Files.createDirectories(outputDirectory);
            return analyze(source, false, outputDirectory);
        }
        for (int i = 2; i < args.length; i++) {
            if (!"--tree".equals(args[i])) {
                throw new IllegalArgumentException("неизвестный параметр: " + args[i]);
            }
        }
        return analyze(source, hasOption(args, "--tree"), null);
    }

    private static Path validateSource(String fileName) {
        Path source = Paths.get(fileName).toAbsolutePath().normalize();
        if (!Files.exists(source) || !Files.isRegularFile(source)) {
            throw new IllegalArgumentException("файл не найден: " + source);
        }
        if (!source.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".java")) {
            throw new IllegalArgumentException("поддерживаются Java-файлы и Java-скрипты с расширением .java.");
        }
        return source;
    }

    private static int managePackages(String[] args) throws IOException {
        if (args.length == 1 || "list".equalsIgnoreCase(args[1]) || "status".equalsIgnoreCase(args[1])) {
            Path project = Paths.get(".").toAbsolutePath().normalize();
            System.out.println("Менеджеры Java-пакетов в проекте " + project + ":");
            printManagerFile(project, "pom.xml", "Maven");
            printManagerFile(project, "build.gradle", "Gradle");
            printManagerFile(project, "build.gradle.kts", "Gradle Kotlin DSL");
            printManagerFile(project, "mvnw", "Maven Wrapper");
            printManagerFile(project, "mvnw.cmd", "Maven Wrapper (Windows)");
            printManagerFile(project, "gradlew", "Gradle Wrapper");
            printManagerFile(project, "gradlew.bat", "Gradle Wrapper (Windows)");
            System.out.println("Команды: packages maven <аргументы Maven> | packages gradle <аргументы Gradle>");
            return 0;
        }

        String manager = args[1].toLowerCase(Locale.ROOT);
        if (!"maven".equals(manager) && !"gradle".equals(manager)) {
            throw new IllegalArgumentException("использование: packages [list|status|maven|gradle] [аргументы менеджера]");
        }
        if (args.length < 3) {
            throw new IllegalArgumentException("укажите команду менеджера, например packages maven dependency:tree.");
        }

        List<String> command = new ArrayList<String>();
        command.add("maven".equals(manager) ? "mvn" : "gradle");
        command.addAll(Arrays.asList(args).subList(2, args.length));
        System.out.println("Запуск менеджера пакетов: " + manager);
        Process process = new ProcessBuilder(command).inheritIO().start();
        try {
            return process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("ожидание команды менеджера пакетов было прервано.", e);
        }
    }

    private static void printManagerFile(Path project, String fileName, String label) {
        Path managerFile = project.resolve(fileName);
        if (Files.exists(managerFile)) {
            System.out.println("  " + label + ": " + managerFile);
        }
    }

    private static int showSavedResults(Path resultsFile, String title) throws IOException {
        if (!Files.isRegularFile(resultsFile)) {
            System.out.println("Сохраненные " + title + " не найдены. Сначала выполните соответствующую команду.");
            return 1;
        }
        System.out.println(new String(Files.readAllBytes(resultsFile), StandardCharsets.UTF_8));
        return 0;
    }

    private static int runTechnicalAnalyzers(Path source) throws IOException {
        JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
        if (javaCompiler == null) {
            System.err.println("Не найден компилятор JDK. Установите JDK и проверьте PATH.");
            return 2;
        }

        String sourceText = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\uFEFF", "");
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager fileManager = javaCompiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        TechnicalMetricsScanner metrics = new TechnicalMetricsScanner();
        try {
            JavaFileObject sourceObject = createSourceFileObject(source);
            List<String> options = Arrays.asList("-proc:none", "-Xlint:all", "-encoding", "UTF-8");
            JavacTask task = (JavacTask) javaCompiler.getTask(
                    null, fileManager, diagnostics, options, null, Arrays.asList(sourceObject));
            for (CompilationUnitTree unit : task.parse()) {
                metrics.scan(unit, null);
            }
            task.analyze();
        } finally {
            fileManager.close();
        }

        StringBuilder report = new StringBuilder();
        report.append("Технический анализ Java-файла: ").append(source).append('\n');
        report.append("Время анализа: ").append(Instant.now()).append('\n');
        report.append("Размер: ").append(Files.size(source)).append(" байт\n");
        report.append("Строки: ").append(countSourceLines(sourceText)).append('\n');
        report.append("Импорты: ").append(metrics.imports).append('\n');
        report.append("Классы/интерфейсы/перечисления: ").append(metrics.classes).append('\n');
        report.append("Методы и конструкторы: ").append(metrics.methods).append('\n');
        report.append("Объявления переменных: ").append(metrics.variables).append('\n');
        report.append("Условия и ветвления: ").append(metrics.branches).append('\n');
        report.append("Циклы: ").append(metrics.loops).append('\n');
        report.append("Вызовы методов: ").append(metrics.invocations).append('\n');
        report.append("Маркеры TODO/FIXME: ").append(countMatches(sourceText, "\\b(?:TODO|FIXME)\\b")).append('\n');
        report.append("Диагностика компилятора:\n");
        if (diagnostics.getDiagnostics().isEmpty()) {
            report.append("  Замечаний нет.\n");
        } else {
            for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
                report.append("  ").append(diagnostic.getKind())
                        .append(" [строка ").append(diagnostic.getLineNumber()).append("]: ")
                        .append(diagnostic.getMessage(Locale.forLanguageTag("ru"))).append('\n');
            }
        }
        report.append("Уровень ветвления (приближенно): ").append(metrics.branches + metrics.loops).append('\n');
        report.append("Примечание: показатели структуры вычисляются по AST; анализ не заменяет тестирование и ревью.\n");

        Files.write(ANALYZER_RESULTS, report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.print(report.toString());
        System.out.println("Результат сохранен в: " + ANALYZER_RESULTS.toAbsolutePath().normalize());
        return countErrors(diagnostics) == 0 ? 0 : 1;
    }

    private static int countMatches(String text, String regex) {
        Matcher matcher = Pattern.compile(regex).matcher(text);
        int count = 0;
        while (matcher.find()) {
            count++;
        }
        return count;
    }

    private static int countSourceLines(String text) {
        if (text.isEmpty()) {
            return 0;
        }
        int lines = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') {
                lines++;
            } else if (text.charAt(i) == '\r' && (i + 1 == text.length() || text.charAt(i + 1) != '\n')) {
                lines++;
            }
        }
        char last = text.charAt(text.length() - 1);
        if (last != '\n' && last != '\r') {
            lines++;
        }
        return lines;
    }

    private static int runDebug(Path source, String[] programArgs) throws IOException {
        Path outputDirectory = Paths.get("out", "debug").toAbsolutePath().normalize();
        Files.createDirectories(outputDirectory);
        System.out.println("Компиляция перед запуском Debug...");
        if (analyze(source, false, outputDirectory) != 0) {
            String report = "Debug не запущен: исходный файл не прошел компиляцию.\n";
            Files.write(DEBUG_RESULTS, report.getBytes(StandardCharsets.UTF_8));
            System.out.print(report);
            return 1;
        }

        String sourceText = new String(Files.readAllBytes(source), StandardCharsets.UTF_8).replace("\uFEFF", "");
        Matcher packageMatcher = Pattern.compile("(?m)^\\s*package\\s+([\\w.]+)\\s*;").matcher(sourceText);
        String className = source.getFileName().toString();
        className = className.substring(0, className.length() - ".java".length());
        if (packageMatcher.find()) {
            className = packageMatcher.group(1) + "." + className;
        }

        String javaExecutable = Paths.get(System.getProperty("java.home"), "bin",
                isWindows() ? "java.exe" : "java").toString();
        List<String> command = new ArrayList<String>();
        command.add(javaExecutable);
        command.add("-ea");
        command.add("-cp");
        command.add(outputDirectory.toString());
        command.add(className);
        command.addAll(Arrays.asList(programArgs));

        Path processOutput = outputDirectory.resolve("debug-process-output.txt");
        Instant started = Instant.now();
        long startNanos = System.nanoTime();
        Process process = new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(processOutput.toFile()).start();
        boolean finished;
        try {
            finished = process.waitFor(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new IOException("ожидание Debug-запуска было прервано.", e);
        }
        if (!finished) {
            process.destroyForcibly();
            try {
                process.waitFor();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("остановка Debug-процесса была прервана.", e);
            }
        }

        String output = Files.exists(processOutput)
                ? new String(Files.readAllBytes(processOutput), StandardCharsets.UTF_8) : "";
        StringBuilder report = new StringBuilder();
        report.append("Результаты Debug-отладки\n");
        report.append("Файл: ").append(source).append('\n');
        report.append("Класс: ").append(className).append('\n');
        report.append("Начало: ").append(started).append('\n');
        report.append("Длительность: ").append(TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startNanos)).append(" мс\n");
        report.append("Статус: ").append(finished ? "процесс завершен" : "остановлен по тайм-ауту (30 секунд)").append('\n');
        report.append(finished ? "Код завершения: " + process.exitValue() : "Код завершения: недоступен").append('\n');
        report.append("Вывод программы:\n").append(output.isEmpty() ? "(пусто)\n" : output);
        if (!output.endsWith("\n")) {
            report.append('\n');
        }
        Files.write(DEBUG_RESULTS, report.toString().getBytes(StandardCharsets.UTF_8));
        System.out.print(report.toString());
        System.out.println("Полный отчет сохранен в: " + DEBUG_RESULTS.toAbsolutePath().normalize());
        return finished && process.exitValue() == 0 ? 0 : 1;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win");
    }

    private static final class TechnicalMetricsScanner extends TreeScanner<Void, Void> {
        private int imports;
        private int classes;
        private int methods;
        private int variables;
        private int branches;
        private int loops;
        private int invocations;

        @Override
        public Void visitImport(com.sun.source.tree.ImportTree tree, Void unused) {
            imports++;
            return super.visitImport(tree, unused);
        }

        @Override
        public Void visitClass(com.sun.source.tree.ClassTree tree, Void unused) {
            classes++;
            return super.visitClass(tree, unused);
        }

        @Override
        public Void visitMethod(com.sun.source.tree.MethodTree tree, Void unused) {
            methods++;
            return super.visitMethod(tree, unused);
        }

        @Override
        public Void visitVariable(com.sun.source.tree.VariableTree tree, Void unused) {
            variables++;
            return super.visitVariable(tree, unused);
        }

        @Override
        public Void visitIf(com.sun.source.tree.IfTree tree, Void unused) {
            branches++;
            return super.visitIf(tree, unused);
        }

        @Override
        public Void visitSwitch(com.sun.source.tree.SwitchTree tree, Void unused) {
            branches++;
            return super.visitSwitch(tree, unused);
        }

        @Override
        public Void visitSwitchExpression(com.sun.source.tree.SwitchExpressionTree tree, Void unused) {
            branches++;
            return super.visitSwitchExpression(tree, unused);
        }

        @Override
        public Void visitConditionalExpression(com.sun.source.tree.ConditionalExpressionTree tree, Void unused) {
            branches++;
            return super.visitConditionalExpression(tree, unused);
        }

        @Override
        public Void visitForLoop(com.sun.source.tree.ForLoopTree tree, Void unused) {
            loops++;
            return super.visitForLoop(tree, unused);
        }

        @Override
        public Void visitEnhancedForLoop(com.sun.source.tree.EnhancedForLoopTree tree, Void unused) {
            loops++;
            return super.visitEnhancedForLoop(tree, unused);
        }

        @Override
        public Void visitWhileLoop(com.sun.source.tree.WhileLoopTree tree, Void unused) {
            loops++;
            return super.visitWhileLoop(tree, unused);
        }

        @Override
        public Void visitDoWhileLoop(com.sun.source.tree.DoWhileLoopTree tree, Void unused) {
            loops++;
            return super.visitDoWhileLoop(tree, unused);
        }

        @Override
        public Void visitMethodInvocation(com.sun.source.tree.MethodInvocationTree tree, Void unused) {
            invocations++;
            return super.visitMethodInvocation(tree, unused);
        }
    }

    private static boolean hasOption(String[] args, String option) {
        for (String arg : args) {
            if (option.equals(arg)) {
                return true;
            }
        }
        return false;
    }

    private static int analyze(Path source, boolean showTree, Path outputDirectory) throws IOException {
        JavaCompiler javaCompiler = ToolProvider.getSystemJavaCompiler();
        if (javaCompiler == null) {
            System.err.println("Не найден компилятор JDK. Установите JDK и проверьте PATH.");
            return 2;
        }

        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<JavaFileObject>();
        StandardJavaFileManager fileManager = javaCompiler.getStandardFileManager(
                diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
        try {
            JavaFileObject unit = createSourceFileObject(source);
            Iterable<? extends JavaFileObject> units = Arrays.asList(unit);
            List<String> options = new ArrayList<String>();
            options.add("-proc:none");
            options.add("-Xlint:all");
            options.add("-encoding");
            options.add("UTF-8");
            if (outputDirectory != null) {
                options.add("-d");
                options.add(outputDirectory.toString());
            }

            JavacTask task = (JavacTask) javaCompiler.getTask(
                    null, fileManager, diagnostics, options, null, units);
            List<CompilationUnitTree> parsedUnits = new ArrayList<CompilationUnitTree>();
            for (CompilationUnitTree parsedUnit : task.parse()) {
                parsedUnits.add(parsedUnit);
            }
            if (showTree) {
                Trees trees = Trees.instance(task);
                for (CompilationUnitTree parsedUnit : parsedUnits) {
                    System.out.println("Структурное дерево: " + source.getFileName());
                    new AstPrinter(parsedUnit, trees.getSourcePositions()).scan(parsedUnit, 0);
                }
            }
            task.analyze();
            if (outputDirectory != null && countErrors(diagnostics) == 0) {
                task.generate();
            }
        } finally {
            fileManager.close();
        }

        printDiagnostics(diagnostics.getDiagnostics());
        long errors = countErrors(diagnostics);
        if (errors == 0) {
            System.out.println(outputDirectory == null
                    ? "Проверка завершена: ошибок компиляции не найдено."
                    : "Компиляция завершена. Классы сохранены в: " + outputDirectory);
            return 0;
        }
        System.out.println("Проверка завершена: ошибок: " + errors + ".");
        return 1;
    }

    private static JavaFileObject createSourceFileObject(Path source) throws IOException {
        final String sourceText = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
        final String normalized = sourceText.replace("\uFEFF", "");
        return new SimpleJavaFileObject(source.toUri(), JavaFileObject.Kind.SOURCE) {
            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) {
                return normalized;
            }
        };
    }

    private static long countErrors(DiagnosticCollector<JavaFileObject> diagnostics) {
        long count = 0;
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics.getDiagnostics()) {
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                count++;
            }
        }
        return count;
    }

    private static void printDiagnostics(List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        if (diagnostics.isEmpty()) {
            System.out.println("Диагностика: замечаний нет.");
            return;
        }
        for (Diagnostic<? extends JavaFileObject> diagnostic : diagnostics) {
            String severity;
            if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                severity = "ОШИБКА";
            } else if (diagnostic.getKind() == Diagnostic.Kind.WARNING
                    || diagnostic.getKind() == Diagnostic.Kind.MANDATORY_WARNING) {
                severity = "ПРЕДУПРЕЖДЕНИЕ";
            } else {
                severity = "СООБЩЕНИЕ";
            }
            String file = diagnostic.getSource() == null
                    ? "<компилятор>" : Paths.get(diagnostic.getSource().toUri()).getFileName().toString();
            System.out.println(severity + " [" + file + ":" + diagnostic.getLineNumber()
                    + ":" + diagnostic.getColumnNumber() + "]: " + diagnostic.getMessage(Locale.forLanguageTag("ru")));
            String advice = recommendation(diagnostic.getMessage(Locale.ROOT));
            if (!advice.isEmpty()) {
                System.out.println("  Совет: " + advice);
            }
        }
    }

    private static String recommendation(String message) {
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("cannot find symbol")) {
            return "Проверьте написание имени, объявление переменной/метода и необходимые import.";
        }
        if (lower.contains("';' expected")) {
            return "Добавьте точку с запятой в указанном месте или проверьте предыдущую строку.";
        }
        if (lower.contains("reached end of file while parsing")) {
            return "Проверьте парные фигурные скобки и завершенность последнего выражения.";
        }
        if (lower.contains("illegal start") || lower.contains("not a statement")) {
            return "Проверьте синтаксис в этой и предыдущей строках: часто причина — лишний или пропущенный знак.";
        }
        if (lower.contains("incompatible types")) {
            return "Сверьте типы присваивания и возвращаемого значения; при необходимости выполните явное преобразование.";
        }
        if (lower.contains("does not exist")) {
            return "Проверьте имя пакета или класса, import и наличие зависимости в classpath.";
        }
        if (lower.contains("is never used") || lower.contains("is not used")) {
            return "Удалите неиспользуемое объявление либо используйте его по назначению.";
        }
        return "";
    }

    private static void formatSource(Path source) throws IOException {
        byte[] bytes = Files.readAllBytes(source);
        String content = new String(bytes, StandardCharsets.UTF_8);
        String newline = content.contains("\r\n") ? "\r\n" : "\n";
        String[] lines = content.split("\\r?\\n", -1);
        StringBuilder formatted = new StringBuilder();
        int lineCount = lines.length;
        if (lineCount > 0 && lines[lineCount - 1].isEmpty()) {
            lineCount--;
        }
        for (int i = 0; i < lineCount; i++) {
            formatted.append(lines[i].replaceAll("[\\t ]+$", ""));
            formatted.append(newline);
        }
        if (lineCount == 0) {
            formatted.append(newline);
        }
        Files.write(source, formatted.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static void printHelp() {
        System.out.println("Java Compiler Scripts Manager " + VERSION);
        System.out.println("Использование:");
        System.out.println("  java compiler                         интерактивный режим");
        System.out.println("  java compiler analyze <файл.java> [--tree]  проверить и показать ошибки/советы");
        System.out.println("  java compiler tree <файл.java>        показать структурное дерево и диагностику");
        System.out.println("  java compiler compile <файл.java> [--out <каталог>]  скомпилировать");
        System.out.println("  java compiler fix <файл.java>         применить безопасное форматирование и проверить");
        System.out.println("  java compiler analyzers <файл.java>    технический AST-анализ, метрики и диагностика");
        System.out.println("  java compiler results                  показать последние результаты анализаторов");
        System.out.println("  java compiler packages [list]           обнаружить Maven/Gradle и wrapper-файлы");
        System.out.println("  java compiler packages maven <команда>  запустить Maven");
        System.out.println("  java compiler packages gradle <команда> запустить Gradle");
        System.out.println("  java compiler debug <файл.java> [-- аргументы]  скомпилировать и запустить программу");
        System.out.println("  java compiler debug-results             показать последний отчет Debug-запуска");
        System.out.println("  connect | disconnect | status | help | exit  команды интерактивного режима");
        System.out.println("Требуется JDK. Проверяются исходники и скрипты Java с расширением .java.");
    }

    private static final class AstPrinter extends TreeScanner<Void, Integer> {
        private final CompilationUnitTree unit;
        private final SourcePositions positions;

        private AstPrinter(CompilationUnitTree unit, SourcePositions positions) {
            this.unit = unit;
            this.positions = positions;
        }

        @Override
        public Void scan(Tree tree, Integer depth) {
            if (tree == null) {
                return null;
            }
            int currentDepth = depth == null ? 0 : depth;
            for (int i = 0; i < currentDepth; i++) {
                System.out.print("  ");
            }
            long start = positions.getStartPosition(unit, tree);
            long line = start < 0 ? -1 : unit.getLineMap().getLineNumber(start);
            System.out.println(tree.getKind() + (line > 0 ? " (строка " + line + ")" : ""));
            return super.scan(tree, currentDepth + 1);
        }
    }
}