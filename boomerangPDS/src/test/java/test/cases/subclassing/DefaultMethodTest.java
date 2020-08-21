package test.cases.subclassing;

import org.junit.Test;
import test.core.AbstractBoomerangTest;

public class DefaultMethodTest extends AbstractBoomerangTest {
  private interface Itf {
    default void work() {}
  }

  private static class Cls implements Itf {}

  @Test
  public void testDefaultMethod() {
    Cls cls = new Cls();
    cls.work();
    queryForAndNotEmpty(cls);
  }
}
