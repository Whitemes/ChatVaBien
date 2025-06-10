package fr.upem.net.chatvabien.server;

import java.io.*;
import java.net.*;

public class MiniClient {
    public static void main(String[] args) throws Exception {
        try (Socket socket = new Socket("localhost", 7777)) {
            OutputStream out = socket.getOutputStream();
            InputStream in = socket.getInputStream();

            // Envoyer LOGIN alice
            byte[] loginData = {
                    0x00,                    // OPCODE LOGIN
                    0x00, 0x00, 0x00, 0x05,  // Taille "alice" = 5
                    0x61, 0x6C, 0x69, 0x63, 0x65  // "alice"
            };

            System.out.println("Envoi LOGIN...");
            out.write(loginData);
            out.flush();

            // Attendre réponse
            Thread.sleep(1000);

            // Envoyer MESSAGE "hello"
            byte[] messageData = {
                    0x04,                    // OPCODE MESSAGE
                    0x00, 0x00, 0x00, 0x05,  // Taille "alice" = 5
                    0x61, 0x6C, 0x69, 0x63, 0x65,  // "alice"
                    0x00, 0x00, 0x00, 0x05,  // Taille "hello" = 5
                    0x68, 0x65, 0x6C, 0x6C, 0x6F   // "hello"
            };

            System.out.println("Envoi MESSAGE...");
            out.write(messageData);
            out.flush();

            System.out.println("Messages envoyés !");
            Thread.sleep(2000);
        }
    }
}