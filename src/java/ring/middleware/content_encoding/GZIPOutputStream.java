package ring.middleware.content_encoding;

import java.io.IOException;
import java.io.OutputStream;

public class GZIPOutputStream extends java.util.zip.GZIPOutputStream
{
    public GZIPOutputStream(OutputStream out, int level) throws IOException
    {
        super(out);
        def.setLevel(level);
    }
}
