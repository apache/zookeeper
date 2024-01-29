package org.apache.zookeeper.cli;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;

public class HexDumpOutputFormatter implements OutputFormatter {

    public static final HexDumpOutputFormatter INSTANCE = new HexDumpOutputFormatter();

    @Override
    public String format(byte[] data) {
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        return ByteBufUtil.prettyHexDump(buf);
    }
}
