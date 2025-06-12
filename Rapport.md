# RAPPORT - PROJET CHATVABIEN

**Noms :** GRIB Rayan - RAMANANJATOVO Johnny  

---

## 1. INTRODUCTION ET CONTEXTE

### 1.1 Objectif du projet
Le projet ChatVaBien vise à développer un système de messagerie instantanée en Java non-bloquant, permettant la communication publique entre utilisateurs et l'établissement de connexions privées P2P pour messages et transferts de fichiers.

### 1.2 Évolution du projet
Le développement s'est articulé en trois phases :
- **1** : Conception RFC
- **2** : Changement de RFC suite aux retours, choix de la RFC de secours
- **3** : Implémentation initiale
- **4** : Soutenance intermédiaire et retours critiques sur l'approche bloquante
- **5** : **Réécriture complète du client** en non-bloquant suite aux remarques

### 1.3 Contexte de développement
Le projet s'est déroulé dans un contexte académique chargé avec :
- Conditionnelles en parallèle pour les deux membres du binôme
- Rattrapages S3 simultanés
- Soucis personnels impactant la charge de travail
- Nécessité de prioriser les fonctionnalités essentielles

---

## 2. ÉTAT DES FONCTIONNALITÉS

### 2.1 Fonctionnalités entièrement opérationnelles

**Authentification sans mot de passe :**
- Connexion client avec pseudonyme unique
- Validation serveur et gestion des doublons
- Diffusion des connexions/déconnexions

**Messages publiques :**
- Broadcast des messages à tous les clients connectés
- Communication bidirectionnelle serveur et clients
- Gestion des déconnexions pendant communication

**Négociation de connexions privées :**
- REQUEST_PRIVATE et OK_PRIVATE/KO_PRIVATE sont implémentés
- Système sur la ligne de commande pour accepter/refuser les demandes
- Échange des informations de connexion a la bais du serveur donc IP, port et token

### 2.2 Fonctionnalités partiellement implémentées

**Établissement concret des connexions P2P :**
- Négociation fonctionnelle
- Architecture PrivateContext créée
- Connexion TCP initiée mais incomplet
- Limitation : Coordination complexe des deux extrémités

**Authentification par mot de passe :**
- Connexion au ServerMDP établie avec succès
- Gestion asynchrone des requêtes/réponses implémentée
- Limitation : Intégration avec le flux d'authentification client non finalisée

### 2.3 Fonctionnalités non implémentées

**Messages privés P2P :** Dépendant de l'ouverture completz des connexions P2P

**Transfert de fichiers :** Protocole FILE(9) défini mais implémentation absente

---

## 3. ARCHITECTURE TECHNIQUE ET CONTRIBUTIONS
```
ChatVaBien-GRIB-RAMANANJATOVO/
├── README.md
├── build.sh
├── consignes.txt
│
├── src/
│   └── fr/upem/net/chatvabien/
│       │
│       ├── protocol/
│       │   ├── OPCODE.java
│       │   ├── Reader.java
│       │   ├── StringReader.java
│       │   ├── IntReader.java
│       │   ├── LongReader.java
│       │   ├── ByteReader.java
│       │   ├── TrameReader.java
│       │   ├── Message.java
│       │   ├── LoginMessage.java
│       │   ├── PublicMessage.java
│       │   ├── PrivateRequestMessage.java
│       │   ├── OKPrivateMessage.java
│       │   ├── KOPrivateMessage.java
│       │   ├── GetUsersMessage.java
│       │   ├── Trame.java
│       │   ├── User.java
│       │   ├── ServerMessageProcessor.java
│       │   └── MessageReader.java
│       │
│       ├── server/
│       │   ├── ChatVaBienServer.java
│       │   ├── passwords.txt
│       │   ├── PrivateRequestSimple.java
│       │   ├── TestSimpleServer.java
│       │   ├── TestSimpleClient.java
│       │   └── MiniClient.java
│       │
│       └── client/
│           ├── ChatVaBienClient.java
│           ├── ServerContext.java
│           ├── PrivateContext.java
│           ├── ConsoleManager.java
│           ├── CommandProcessor.java
│           ├── DefaultCommandProcessor.java
│           ├── ServerMessageHandler.java
│           └── ChannelHandler.java
│
└── dist/
    ├── chatvabien-server.jar
    ├── chatvabien-client.jar
    ├── chatvabien-client-legacy.jar
    └── passwords.txt
```

### 3.1 Architecture protocole

**Messages avec interface scellée :**
```java
public sealed interface Message permits LoginMessage, PublicMessage, 
    PrivateRequestMessage, OKPrivateMessage, KOPrivateMessage, GetUsersMessage {
    ByteBuffer serialize();
    void process(ServerMessageProcessor processor);
}
```
**Avantages :** Permet une simplification du traitement des requetes (une sorte de packet Trame avec un ByteBuffer inclus avec les données qur'il faut directement accesible a la création).

**Traitment non-bloquant :**
```java
public class TrameReader implements Reader<Trame> {
    private enum State { WAITING_OPCODE, WAITING_SENDER, WAITING_MESSAGE, DONE, ERROR }
    private State state = WAITING_OPCODE;
    private byte opcode;
    ...
    switch(opcode){
        case WAITING_OPCODE -> {
            ...
            state = State.DONE;
        }
    }
}
```
### 3.2 Architecture client
**Modularité après réécriture :**
- `ChatVaBienClient` : Orchestration Event Loop
- `ServerContext` : Communication serveur principal  
- `PrivateContext` : Gestion connexions P2P
- `ConsoleManager` : Interface utilisateur non-bloquante

### 3.3 Répartition des contributions

**Serveur (Rayan 40% - Johnny 60%) :**
- Rayan : Architecture initiale, concepts fondamentaux, idées directrices
- Johnny : Reprise et finalisation, refactoring, optimisations, tests, correction post-soutenance

**Client (Rayan 60% - Johnny 40%) :**
- Rayan : Réécriture post-soutenance, architecture non-bloquante, ServerContext
- Johnny : PrivateContext, CommandProcessor, interface utilisateur

**Justification :** Évolution naturelle basée sur l'expertise acquise. Rayan a développé developé via le serveur puis l'a appliquée au client. Johnny a optimisé et finalisé le serveur initial de Rayan.

---

## 4. RÉPONSES AUX REMARQUES DE SOUTENANCE INTERMÉDIAIRE

### 4.1 "Code client bloquant au lieu de non-bloquant"

**Problème identifié :**
```java
// Incorret car bloquant
socket.connect(serverAddress);
...
channel.write(messageBuffer); 
```

**Solution : Réécriture complète**
```java
@Override
public void handleConnect() throws IOException {
    if (sc.finishConnect()) {
        connected = true;
        updateInterestOps();
    }
}
```
**Résultat :** Architecture non-bloquante plus cohérente.

**Event Loop non-bloquant :**
```java
selector.select(this::treatKey);
private void treatKey(SelectionKey key) {
    // Gestion événements réseau avec Context
}
```
**Performance :** Permet d'avoir du vrai non bloquant dans l'ensemble du projet

### 4.2 "Utilisation inappropriée de selectNow()"

**Problème :** Attente active causant une consommation CPU excessive
```java
// Avant dans ClientTest
selector.selectNow();

// provoque de l'attente active, cette methode a été supprimée à chaque occurance dans le projet
```
**Résultat :** Réduction de l'utilisation CPU au repos.

### 4.3 "Architecture trop complexe"

**Simplification :**
```java
// Interface des context
public interface ChannelHandler {
    default void handleConnect() throws IOException {}
    default void handleRead() throws IOException {}
    default void handleWrite() throws IOException {}
}
```
**Résultat :** Du code en moins, architecture compréhensible.

### 4.4 "Gestion d'erreurs insuffisante"

**Amélioration :**
```java
private void silentlyClose(SelectionKey key) {
    try {
        var context = (Context) key.attachment();
        if (context != null) {
            context.cleanup(); // Nettoyage métier
            if (context.pseudo != null) {
                connectedUsers.remove(context.pseudo);
                broadcastUserDisconnection(context.pseudo);
            }
        }
        key.channel().close();
    } catch (IOException e) {
        // Graceful degradation
    }
}
```
**Résultat :** Gestion robuste des déconnexions, uptime > 24h sans redémarrage.

---

## 5. DÉFIS TECHNIQUES ET APPRENTISSAGES

### 5.1 Problèmes rencontrés et solutions

**Utilisation initiale de LLM :**
Sous pression temporelle, nous avons tenté d'utiliser des assistants IA qui ont généré du code inadapté (approche bloquante, `selectNow()` inapproprié). <br>
--> Retour aux fondamentaux (TPs, cours) plus efficace pour du code système complexe.

**Complexité des connexions P2P :**
La coordination entre client initiateur et client cible s'est révélée plus complexe que prévu, nécessitant une gestion d'états sophistiquée non finalisée dans les délais.

**Contraintes académiques :**
La surcharge de travail (conditionnelles pour Johnny (fin du projet) et Rayan (début du projet), rattrapage S3) a imposé une priorisation stricte des fonctionnalités, simulant les contraintes réelles de développement professionnel.

**Contraintes personnelles**
Rayan a eu quelques soucis personnels depuis quelques mois a impacté sur ses performances sur le projet en général.

### 5.2 Compétences développées

**Techniques :**
- Gestion des buffers et de la programation réseau Java non-bloquant
- Architecture de communication client-serveur et patterns  au traitement des données
- Utilisation plus profonde de TCP

**Méthodologiques :**
- Collaboration technique en binôme
- Gestion de projet sous contraintes temporelles
- Capacité d'adaptation et de refactoring complet

---

## 6. CONCLUSION

### 6.1 Bilan du projet

Le projet ChatVaBien a eu beaucoup de contraintes qui ont été rencontrées. Trois fonctionnalités principales sont opérationnelles : authentification, messagerie brodcast, et négociation de connexions privées.

La réécriture du client après la soutenance intermédiaire illustre de bien s'organiser et de prévoir les choses. Cela fut de l'apprentissage. Cette démarche a permis de nous améliorer sur les concepts réseau et de produire une meilleure architecture.

### 6.2 Valeur pédagogique

**Points forts :**
- **Architecture protocole plus complète** utilisant les standards Java modernes  
- **Communication robuste** pour les fonctionnalités de base
- **Apprentissage approfondi** des concepts de programmation réseau

**Limitations assumées :**
- **Connexions P2P partielles** : Négociation complète, établissement canal à finaliser
- **Auth MDP incomplète** : Infrastructure présente, logique métier à compléter

### 6.3 Perspective professionnelle

Le projet constitue une experience technique pour mieux comprendre des fonctionnalités avancées d'un système de chat et de discussion en ligne.

---

**En conclusion**, ChatVaBien représente une experiance qui permet d'apprendre les notions de programmation réseau en TCP mais aussi à devoir s'adapter aux contraintes, et à produire du code plus respectueux de la logique Java et de la programmation réseau en général. Malgré les soucis durant le projet, nous comprenons l'importance de savoir métriser les notions présentées en cours et de pouvoir les appliquer dans ce contexte.