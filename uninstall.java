import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

public class uninstall {
    private static final String INSTALL_DIRECTORY = ".java-compiler-scripts-manager";

    public static void main(String[] args) {
        System.out.println("Java Compiler Scripts Manager Setup Uninstall (JCSM Setup Uninstall)");
        try {
            if (!confirmUninstall()) {
                return;
            }

            String userHome = System.getProperty("user.home");
            if (userHome == null || userHome.trim().isEmpty()) {
                throw new IOException("Не удалось определить домашнюю папку пользователя.");
            }
            Path installDirectory = Paths.get(userHome)
                    .toAbsolutePath().normalize().resolve(INSTALL_DIRECTORY);
            uninstall(installDirectory);
        } catch (IOException | SecurityException | InvalidPathException e) {
            printFailure(e);
            System.exit(1);
        }
    }

    private static boolean confirmUninstall() throws IOException {
        if (GraphicsEnvironment.isHeadless()) {
            throw new IOException("Невозможно открыть диалог подтверждения в этой среде.");
        }

        final int[] result = new int[1];
        try {
            SwingUtilities.invokeAndWait(new Runnable() {
                @Override
                public void run() {
                    result[0] = JOptionPane.showOptionDialog(
                            null,
                            "Вы точно хотите удалить компилятор?",
                            "Удаление компилятора",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE,
                            null,
                            new Object[] { "Да", "Нет" },
                            "Нет");
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Ожидание диалога подтверждения прервано.", e);
        } catch (InvocationTargetException e) {
            throw new IOException("Не удалось показать диалог подтверждения.", e);
        }
        return result[0] == 0;
    }

    private static void uninstall(Path installDirectory) throws IOException {
        if (Files.notExists(installDirectory, LinkOption.NOFOLLOW_LINKS)) {
            showProgress(100);
            System.out.println("Компилятор успешно удалился (он уже отсутствовал).");
            System.out.println("Результат: папка установки не найдена: " + installDirectory);
            return;
        }
        if (Files.isSymbolicLink(installDirectory)) {
            throw new IOException("Путь установки является символической ссылкой; содержимое не удалено: "
                    + installDirectory);
        }
        if (!Files.isDirectory(installDirectory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Путь установки не является папкой; файл не удален: " + installDirectory);
        }

        final AtomicLong total = new AtomicLong();
        Files.walkFileTree(installDirectory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                total.incrementAndGet();
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
                total.incrementAndGet();
                return FileVisitResult.CONTINUE;
            }
        });

        System.out.println("Удаление Java Compiler Scripts Manager...");
        final AtomicLong removed = new AtomicLong();
        final long itemCount = total.get();
        Files.walkFileTree(installDirectory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                Files.delete(file);
                updateProgress(removed.incrementAndGet(), itemCount);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path directory, IOException exception) throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(directory);
                updateProgress(removed.incrementAndGet(), itemCount);
                return FileVisitResult.CONTINUE;
            }
        });

        showProgress(100);
        System.out.println("Компилятор успешно удалился.");
        System.out.println("Результат: удалена папка установки " + installDirectory);
        System.out.println("Удалено файлов и папок: " + removed.get());
    }

    private static void updateProgress(long completed, long total) {
        int percent = (int) Math.min(99, completed * 100 / total);
        showProgress(percent);
    }

    private static void showProgress(int percent) {
        int width = 30;
        int filled = percent * width / 100;
        StringBuilder bar = new StringBuilder("[");
        for (int i = 0; i < width; i++) {
            bar.append(i < filled ? '#' : '-');
        }
        bar.append("] ").append(percent).append("%");
        System.out.print("\r" + bar);
        if (percent == 100) {
            System.out.println();
        }
    }

    private static void printFailure(Exception exception) {
        System.err.println();
        System.err.println("Не удалось удалить компилятор. Обратитесь к системному администратору");
        System.err.println("Результат: " + exception.getMessage());
    }
}