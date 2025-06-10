package fr.upem.net.chatvabien.server;

import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;

public class TestSimpleClient {
    public static void main(String[] args) throws Exception {
        try (Socket socket = new Socket("localhost", 7777)) {
            OutputStream out = socket.getOutputStream();

            // Envoyer exactement les mêmes bytes que le client
            byte[] data = {0x00, 0x00, 0x00, 0x00, 0x05, 0x61, 0x6C, 0x69, 0x63, 0x65};

            System.out.println("Envoi des bytes:");
            for (byte b : data) {
                System.out.printf("%02X ", b);
            }
            System.out.println();

            out.write(data);
            out.flush();

            System.out.println("Données envoyées avec succès");
            Thread.sleep(2000); // Laisser le temps au serveur
        }
    }
}