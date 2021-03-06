package org.jooby.integration;

import static org.junit.Assert.assertEquals;

import java.util.List;
import java.util.Optional;

import javax.inject.Named;

import org.apache.http.client.fluent.Request;
import org.apache.http.client.utils.URIBuilder;
import org.jooby.Status;
import org.jooby.mvc.GET;
import org.jooby.mvc.Header;
import org.jooby.mvc.Path;
import org.jooby.test.ServerFeature;
import org.junit.Test;

public class HeaderParamFeature extends ServerFeature {

  public enum VOWELS {
    A,
    B;
  }

  public static class Resource {

    @GET
    @Path("/boolean")
    public Object booleanHeader(@Header final boolean h) {
      return h;
    }

    @GET
    @Path("/booleanList")
    public Object booleanHeader(@Header final List<Boolean> h) {
      return h.toString();
    }

    @GET
    @Path("/booleanOptional")
    public Object booleanHeader(@Header final Optional<Boolean> h) {
      return h.toString();
    }

    @GET
    @Path("/modifiedSince")
    public Object modifiedSince(@Named("If-Modified-Since") @Header final Optional<Long> h) {
      return h.orElse(-1l);
    }

    @GET
    @Path("/int")
    public Object intHeader(@Header final int h) {
      return h;
    }

    @GET
    @Path("/enum")
    public Object enumHeader(@Header final VOWELS h) {
      return h;
    }

  }

  {
    use(Resource.class);
  }

  @Test
  public void booleanHeader() throws Exception {
    assertEquals("true", execute(GET(uri("boolean")).addHeader("h", "true")));
  }

  @Test
  public void booleanListHeader() throws Exception {
    assertEquals("[true, false]",
        execute(GET(uri("booleanList")).addHeader("h", "true").addHeader("h", "false")));
  }

  @Test
  public void booleanOptionalHeader() throws Exception {
    assertEquals("Optional.empty", execute(GET(uri("booleanOptional"))));

    assertEquals("Optional[true]", execute(GET(uri("booleanOptional")).addHeader("h", "true")));
  }

  @Test
  public void modifiedSince() throws Exception {
    assertEquals("-1", execute(GET(uri("modifiedSince"))));

    assertEquals("1405373709000",
        execute(GET(uri("modifiedSince")).addHeader("If-Modified-Since",
            "Mon, 14 Jul 2014 21:35:09 GMT")));
  }

  @Test
  public void missingHeader() throws Exception {
    assertStatus(Status.BAD_REQUEST, () -> execute(GET(uri("int"))));
  }

  @Test
  public void intHeader() throws Exception {
    assertEquals("302", execute(GET(uri("int")).addHeader("h", "302")));
  }

  @Test
  public void enumHeader() throws Exception {
    assertEquals("A", execute(GET(uri("enum")).addHeader("h", "A")));
  }

  private static Request GET(final URIBuilder uri) throws Exception {
    return Request.Get(uri.build());
  }

  private static String execute(final Request request) throws Exception {
    return request.execute().returnContent().asString();
  }

}
