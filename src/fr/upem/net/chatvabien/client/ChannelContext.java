package fr.upem.net.chatvabien.client;

import java.io.IOException;

interface ChannelContext {
    void doConnect() throws IOException;
    void doRead() throws IOException;
    void doWrite() throws IOException;
}
