package fr.upem.net.chatvabien.server;

import java.io.*;
import java.net.*;

public class TestSimpleServer {
    public static void main(String[] args) throws Exception {
        try (ServerSocket serverSocket = new ServerSocket(7777)) {
            System.out.println("Serveur en écoute sur port 7777");

            try (Socket clientSocket = serverSocket.accept()) {
                InputStream in = clientSocket.getInputStream();

                System.out.println("Client connecté");

                byte[] buffer = new byte[1024];
                int bytesRead = in.read(buffer);

                System.out.println("Bytes lus: " + bytesRead);
                System.out.print("Contenu: ");
                for (int i = 0; i < bytesRead; i++) {
                    System.out.printf("%02X ", buffer[i]);
                }
                System.out.println();
            }
        }
    }
}