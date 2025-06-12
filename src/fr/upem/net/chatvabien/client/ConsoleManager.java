package fr.upem.net.chatvabien.client;

import java.util.Scanner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.logging.Logger;

/**
 * Gestionnaire de la console utilisateur - VERSION PROPRE
 */
public class ConsoleManager {
    private static final Logger logger = Logger.getLogger(ConsoleManager.class.getName());

    private final BlockingQueue<String> commandQueue = new ArrayBlockingQueue<>(100);
    private final Thread consoleThread;

    public ConsoleManager() {
        this.consoleThread = Thread.ofPlatform().daemon().unstarted(this::consoleRun);
    }

    public void start() {
        consoleThread.start();
        // ✅ SUPPRIMÉ: Log de démarrage
    }

    public String pollCommand() {
        return commandQueue.poll();
    }

    private void consoleRun() {
        try (var scanner = new Scanner(System.in)) {
            System.out.println("🚀 Client démarré. Tapez vos messages ou /help pour l'aide");
            System.out.print("> ");
            System.out.flush();

            while (scanner.hasNextLine() && !Thread.currentThread().isInterrupted()) {
                var line = scanner.nextLine().trim();

                if (!line.isEmpty()) {
                    boolean offered = commandQueue.offer(line);

                    if (!offered) {
                        System.err.println("⚠️ Queue pleine, commande ignorée");
                    }
                }

                System.out.print("> ");
                System.out.flush();
            }
        } catch (Exception e) {
            logger.severe("Erreur thread console: " + e.getMessage());
        }
        // ✅ SUPPRIMÉ: Log de fin
    }
}