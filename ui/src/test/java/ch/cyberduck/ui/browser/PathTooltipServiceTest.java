package ch.cyberduck.ui.browser;

import ch.cyberduck.core.AbstractTestCase;
import ch.cyberduck.core.Path;

import org.junit.Test;

import java.util.EnumSet;

import static org.junit.Assert.assertEquals;

/**
 * @version $Id$
 */
public class PathTooltipServiceTest extends AbstractTestCase {

    @Test
    public void testGetTooltip() throws Exception {
        final PathTooltipService s = new PathTooltipService();
        assertEquals("/p\n" +
                "--\n" +
                "Unknown", s.getTooltip(new Path("/p", EnumSet.of(Path.Type.file))));
    }
}