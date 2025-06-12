# ChatVaBien-GRIB-RAMANANJATOVO

## Vue d'ensemble

Système de messagerie non-bloquant en Java NIO avec messages publics, connexions privées P2P et transfert de fichiers.

## Architecture

### Structure du projet
```
src/fr/upem/net/chatvabien/
├── protocol/                    # Protocol unifié
│   ├── *Message.java           # Messages (Login, Public, Private...)
│   ├── OPCODE.java             # Énumération opcodes
│   ├── Trame.java              # Trame réseau
│   ├── TrameReader.java        # Lecteur de trames
│   └── *Reader.java            # Lecteurs primitifs
├── server/                      # Serveur non-bloquant
│   ├── ChatVaBienServer.java   # Serveur principal 
│   └── ClientTest.java         # Client de test
└── client/                      # Client refactorisé
    └── ChatVaBienClient.java   # Client principal
```

## Compilation et lancement

### Compilation
```bash
cd src
javac fr/upem/net/chatvabien/**/*.java
```

### Serveur
```bash
# Mode simple (sans authentification)
java fr.upem.net.chatvabien.server.ChatVaBienServer 7777

# Mode avec authentification MDP
java -jar ServerMDP.jar 8888 passwords.txt &
java fr.upem.net.chatvabien.server.ChatVaBienServer 7777 8888
```

### Client
```bash
java fr.upem.net.chatvabien.client.ChatVaBienClient <login> <host> <port> <fileDir>
```

## Commandes

### Messages publics
```
Hello everyone!              # Message public
```

### Connexions privées
```
@bob Hello                    # Message privé
accept alice                  # Accepter demande privée
refuse bob                    # Refuser demande privée
```

### Système
```
/users                        # Lister utilisateurs connectés
/help                         # Aide
/quit                         # Quitter
```

## État d'avancement

### Fonctionnalités terminées
- Authentification sans mot de passe
- Messages publics
- Négociation connexions privées
- Liste des utilisateurs connectés
- Architecture modulaire refactorisée

### Fonctionnalités partielles
- Messages privés P2P (infrastructure présente)
- Transfert de fichiers (partiellement implémenté)
- Authentification avec mot de passe (serveur MDP intégré)

### À compléter
- Messages texte sur connexions privées
- Transfert de fichiers robuste
- Tests complets authentification MDP

## Protocol réseau

### Format trame
```
+----------+-------------+--------+----------+
| OPCODE   | Sender Size | Sender | Payload  |
| (1 byte) | (4 bytes)   | (UTF8) | Variable |
+----------+-------------+--------+----------+
```

### Opcodes principaux
- `0x00` LOGIN
- `0x02` LOGIN_ACCEPTED, `0x03` LOGIN_REFUSED
- `0x04` MESSAGE
- `0x05` REQUEST_PRIVATE, `0x06` OK_PRIVATE, `0x07` KO_PRIVATE
- `0x08` OPEN, `0x09` FILE
- `0x11` GET_CONNECTED_USERS, `0x12` CONNECTED_USERS_LIST

## Tests

### Test de base
```bash
# Terminal 1: Serveur
java -jar ChatVaBienServer.jar 7777

# Terminal 2: Alice  
java -jar ChatVaBienClient.jar alice localhost 7777 ./files

# Terminal 3: Bob
java -jar ChatVaBienClient.jar bob localhost 7777 ./files
```

### Test avec authentification
```bash
# Terminal 1: ServerMDP
java -jar ServerMDP.jar 8888 passwords.txt

# Terminal 2: Serveur
java -jar ChatVaBienServer.jar 7777 8888

```