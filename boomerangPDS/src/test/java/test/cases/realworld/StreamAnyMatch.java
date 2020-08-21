package test.cases.realworld;

import org.junit.Test;
import test.core.AbstractBoomerangTest;

import java.util.ArrayList;
import java.util.List;

public class StreamAnyMatch extends AbstractBoomerangTest {
    @Test
    public void anyMatch() {
        List<String> strings = new ArrayList<>();

        strings.stream().anyMatch(x -> true);
        queryForAndNotEmpty(strings);
    }
}
