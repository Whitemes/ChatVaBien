package fr.upem.net.chatvabien.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.util.Map;

import fr.upem.net.chatvabien.protocol.PublicMessage;

/**
 * Implémentation par défaut du processeur de commandes
 */
public class DefaultCommandProcessor implements CommandProcessor {

    private final ChatVaBienClient client;
    private final ServerContext serverContext;
    private final Map<String, PrivateContext> privateContexts;
    private final ServerSocketChannel privateServerChannel;

    public DefaultCommandProcessor(ChatVaBienClient client, ServerContext serverContext,
                                   Map<String, PrivateContext> privateContexts,
                                   ServerSocketChannel privateServerChannel) {
        this.client = client;
        this.serverContext = serverContext;
        this.privateContexts = privateContexts;
        this.privateServerChannel = privateServerChannel;
    }

    @Override
    public void processCommand(String command) {
        try {
            if (command.startsWith("/")) {
                handleSpecialCommand(command);
            } else if (command.startsWith("@")) {
                handlePrivateMessage(command);
            } else if (command.startsWith("accept ")) {
                handleAcceptPrivate(command.substring(7));
            } else if (command.startsWith("refuse ")) {
                handleRefusePrivate(command.substring(7));
            } else {
                handlePublicMessage(command);
            }
        } catch (Exception e) {
            System.err.println("Erreur commande: " + e.getMessage());
        }
    }

    private void handleSpecialCommand(String command) {
        switch (command) {
            case "/help" -> showHelp();
            case "/users" -> {
                client.handleUsersCommand();
            }
            case "/quit" -> System.exit(0);
            default -> {
                if (command.startsWith("/")) {
                    System.out.println("Commande inconnue. Tapez /help");
                }
            }
        }
    }

    private void handlePublicMessage(String message) {
        var publicMsg = new PublicMessage(message);
        serverContext.queueMessage(publicMsg);
    }

    private void handlePrivateMessage(String command) {
        var parts = command.substring(1).split(" ", 2);
        if (parts.length < 1) {
            System.out.println("Usage: @pseudo [message]");
            return;
        }

        var targetPseudo = parts[0];

        var privateContext = privateContexts.get(targetPseudo);
        if (privateContext != null && privateContext.isOpened()) {
            if (parts.length == 2) {
                privateContext.sendPrivateMessage(parts[1]);
                System.out.println("[PRIVÉ] -> " + targetPseudo + ": " + parts[1]);
            } else {
                System.out.println("Connexion privée active avec " + targetPseudo);
            }
        } else {
            serverContext.queuePrivateRequest(targetPseudo);
            System.out.println("Demande de connexion privée envoyée à " + targetPseudo);
        }
    }

    private void handleAcceptPrivate(String requester) {
        try {
            var localAddress = (InetSocketAddress) privateServerChannel.getLocalAddress();
            var token = System.currentTimeMillis();

            serverContext.queueOKPrivate(requester, localAddress, token);
            System.out.println("Connexion privée acceptée avec " + requester);

        } catch (IOException e) {
            System.err.println("Erreur acceptation privée: " + e.getMessage());
        }
    }

    private void handleRefusePrivate(String requester) {
        serverContext.queueKOPrivate(requester);
        System.out.println("Connexion privée refusée avec " + requester);
    }

    @Override
    public void showHelp() {
        System.out.println("""
            Aide ChatVaBien:
            
            Messages publics:
              <message>           - Envoyer un message public
            
            Messages privés:
              @pseudo [message]   - Demande connexion privée (+ message optionnel)
              accept <pseudo>     - Accepter demande de connexion privée
              refuse <pseudo>     - Refuser demande de connexion privée
            
            Commandes:
              /users              - Lister les utilisateurs connectés
              /help               - Afficher cette aide
              /quit               - Quitter
            """);
    }
}