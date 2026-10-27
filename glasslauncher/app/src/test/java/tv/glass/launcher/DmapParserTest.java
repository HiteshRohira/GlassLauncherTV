package tv.glass.launcher;
import static org.junit.Assert.*;
import io.github.jqssun.airplay.audio.DmapParser;
import java.nio.ByteBuffer;
import java.util.Map;
import org.junit.Test;
public class DmapParserTest {
    private static byte[] item(String tag,byte[] body) {
        return ByteBuffer.allocate(8+body.length).put(tag.getBytes(java.nio.charset.StandardCharsets.US_ASCII)).putInt(body.length).put(body).array();
    }
    @Test public void retainsNormalNestedMetadata() {
        Map<String,Object> result=DmapParser.INSTANCE.parse(item("mlit",item("minm","A song".getBytes())));
        assertEquals("A song",((Map<?,?>)result.get("mlit")).get("minm"));
    }
    @Test public void rejectsDeepNestingBeforeQuadraticAllocations() {
        byte[] value=new byte[0];
        for(int i=0;i<100;i++)value=item("mlit",value);
        try { DmapParser.INSTANCE.parse(value);fail("depth limit"); }
        catch(IllegalArgumentException expected) {}
    }
    @Test public void rejectsTruncatedAndOversizedIntegerFields() {
        byte[] bad=ByteBuffer.allocate(8).putInt(0x6d696e6d).putInt(100).array();
        try { DmapParser.INSTANCE.parse(bad);fail("truncated"); } catch(IllegalArgumentException expected) {}
        try { DmapParser.INSTANCE.parse(item("astm",new byte[9]));fail("integer width"); } catch(IllegalArgumentException expected) {}
    }
}
