package org.jooby.integration;

import static org.junit.Assert.assertEquals;

import org.apache.http.HttpResponse;
import org.apache.http.client.fluent.Request;
import org.apache.http.client.utils.URIBuilder;
import org.jooby.integration.FilterFeature.HttpResponseValidator;
import org.jooby.mvc.GET;
import org.jooby.mvc.Path;
import org.jooby.test.ServerFeature;
import org.junit.Test;

public class MvcNoContentFeature extends ServerFeature {

  public static class Resource {
    @GET
    @Path("/")
    public void noContent() {

    }
  }

  {
    use(Resource.class);
  }

  @Test
  public void noContent() throws Exception {
    assertEquals(null, execute(GET(uri("/")), (response) -> {
      assertEquals(204, response.getStatusLine().getStatusCode());
    }));
  }

  private static Request GET(final URIBuilder uri) throws Exception {
    return Request.Get(uri.build());
  }

  private static Object execute(final Request request, final HttpResponseValidator validator)
      throws Exception {
    HttpResponse resp = request.execute().returnResponse();
    validator.validate(resp);
    return resp.getEntity();
  }
}
