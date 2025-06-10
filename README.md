# ChatVaBien-GRIB-RAMANANJATOVO 🚀

## 📋 Vue d'ensemble

Système de messagerie non-bloquant en Java NIO avec architecture refactorisée selon les bonnes pratiques du cours. Support des messages publics, connexions privées P2P, transfert de fichiers et authentification.

## 🏗️ Architecture refactorisée

### Structure du projet
```
src/fr/upem/net/chatvabien/
├── protocol/                    # Protocol refactorisé
│   ├── *Message.java           # Messages scellés (Login, Public, Private...)
│   ├── OPCODE.java             # Énumération opcodes
│   ├── Trame.java              # Trame réseau unifiée
│   ├── TrameReader.java        # Lecteur de trames
│   └── *Reader.java            # Lecteurs primitifs (String, Int...)
├── server/                      # Serveur non-bloquant
│   ├── ChatVaBienServer.java   # Serveur principal 
│   └── ClientTest.java         # Client legacy (fonctionnel)
└── client/                      # Client refactorisé
    └── ChatVaBienClient.java   # Nouveau client (en debug)
```

## 🚀 Lancement

### Compilation
```bash
cd src
javac fr/upem/net/chatvabien/**/*.java
```

### Serveur
```bash
# Mode simple (sans authentification MDP)
java fr.upem.net.chatvabien.server.ChatVaBienServer 7777

# Mode avec authentification MDP
java -jar ServerMDP.jar 8888 passwords.txt &
java fr.upem.net.chatvabien.server.ChatVaBienServer 7777 8888
```

### Client

#### Client refactorisé (EN DEBUG 🚨)
```bash
java fr.upem.net.chatvabien.client.ChatVaBienClient <login> <host> <port> <fileDir>
# Exemple: java fr.upem.net.chatvabien.client.ChatVaBienClient alice localhost 7777 ./files
```

#### Client legacy (FONCTIONNEL ✅)
```bash
java fr.upem.net.chatvabien.server.ClientTest <login> <password> <fileDir>
# Exemple: java fr.upem.net.chatvabien.server.ClientTest alice "" ./files
```

## 💬 Commandes

### Messages publics
```
Hello everyone!              # Message public à tous
```

### Connexions privées
```
@bob Hello                    # Message privé (crée connexion si nécessaire)
accept alice                  # Accepter demande privée
refuse bob                    # Refuser demande privée
```

### Système
```
/users                        # Lister utilisateurs connectés
/help                         # Aide
/quit                         # Quitter
```

## 📊 État des fonctionnalités

### ✅ Implémentées et testées
1. **Authentification sans MDP** - `ClientTest` ✅
2. **Messages publics** - `ClientTest` ✅
3. **Négociation connexions privées** - `ClientTest` ✅

### 🟡 Implémentées (architecture refactorisée)
4. **Initiation connexions privées** - Nouveau client 🔧
5. **Messages texte privés** - Nouveau client 🔧
6. **Transfert fichiers** - `ClientTest` partiellement ⚠️
7. **Authentification MDP** - Infrastructure présente 🔧

### 🚨 Problème critique actuel
- **Nouveau client** : Communication client→serveur défaillante (serveur reçoit des zéros)
- **Symptôme** : Client envoie correctement, serveur reçoit `00 00 00...`
- **Workaround** : Utiliser `ClientTest` en attendant résolution

## 🔧 Protocol réseau

### Format trame
```
+----------+-------------+--------+----------+
| OPCODE   | Sender Size | Sender | Payload  |
| (1 byte) | (4 bytes)   | (UTF8) | Variable |
+----------+-------------+--------+----------+
```

### Opcodes
- `0x00` LOGIN, `0x02` LOGIN_ACCEPTED, `0x03` LOGIN_REFUSED
- `0x04` MESSAGE, `0x05` REQUEST_PRIVATE, `0x06` OK_PRIVATE, `0x07` KO_PRIVATE
- `0x08` OPEN, `0x09` FILE, `0x11` GET_CONNECTED_USERS, `0x12` CONNECTED_USERS_LIST

## 👥 Répartition travail

### Rayan
- ✅ **Authentification sans MDP** - Terminé
- ✅ **Messages publics** - Terminé
- ✅ **Négociation connexions privées** - Terminé
- 🔧 **Authentification avec MDP** - Infrastructure prête

### Johnny
- 🔧 **Initiation connexions privées** - Architecture présente
- 🔧 **Messages texte privés** - En cours
- ⚠️ **Fichiers connexions privées** - Partiellement fait

### Binôme
- ✅ **Refactoring architecture** - Terminé
- 🚨 **Debug communication client** - Critique à résoudre

## 🎯 Prochaines étapes

### Priorité 1 (URGENT)
- 🚨 **Résoudre bug communication** nouveau client
- 🧪 **Test complet** avec `ClientTest` (fonctionnel)

### Priorité 2
- 🔧 **Finaliser nouveau client** une fois debug résolu
- ✅ **Compléter messages privés**
- 📁 **Transfert fichiers** robuste

### Priorité 3
- 🔐 **Test authentification MDP**
- 📚 **Documentation** complète
- ⚡ **Optimisations** performance

## 🧪 Tests recommandés

### Test base (ClientTest)
```bash
# Terminal 1: Serveur
java fr.upem.net.chatvabien.server.ChatVaBienServer 7777

# Terminal 2: Alice  
java fr.upem.net.chatvabien.server.ClientTest alice "" ./files

# Terminal 3: Bob
java fr.upem.net.chatvabien.server.ClientTest bob "" ./files
```

**Résultat attendu** : Messages publics + connexions privées fonctionnent

### Test authentification MDP
```bash
# Terminal 1: ServerMDP
java -jar ServerMDP.jar 8888 passwords.txt

# Terminal 2: Serveur
java fr.upem.net.chatvabien.server.ChatVaBienServer 7777 8888

# Terminal 3: Client avec compte
java fr.upem.net.chatvabien.server.ClientTest arnaud townhall ./files
```

## 📈 Bilan refactoring

### ✅ Réussites
- **Architecture propre** : Responsabilités séparées, polymorphisme
- **Code réduit** : 3x moins de lignes que version originale
- **Protocol robuste** : Messages scellés, `ByteBuffer` exclusif
- **Serveur optimisé** : Context simplifié, broadcast efficace

### 🚨 Point bloquant
- **Communication client** : Bug critique à résoudre dans nouveau client
- **Solution temporaire** : `ClientTest` entièrement fonctionnel

### 🎯 Objectif final
Une fois le bug résolu, l'architecture refactorisée permettra une implémentation complète et robuste de toutes les fonctionnalités avec un code maintenable et performant.

---

**Note** : Utiliser `ClientTest` pour les démonstrations en attendant la résolution du bug de communication du nouveau client. L'architecture refactorisée est solide et prête pour l'implémentation finale.