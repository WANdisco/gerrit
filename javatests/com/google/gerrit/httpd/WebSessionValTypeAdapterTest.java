package com.google.gerrit.httpd;

import com.google.gerrit.entities.Account;
import com.google.gerrit.server.account.externalids.ExternalIdKeyFactory;
import com.google.gerrit.server.config.AuthConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.lang.reflect.Field;

import static org.junit.Assert.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@RunWith(MockitoJUnitRunner.class)
public class WebSessionValTypeAdapterTest {

    @Mock private AuthConfig authConfig;

    private ExternalIdKeyFactory extIdKeyFactory;

    private static final Account.Id AUTH_ACCOUNT_ID = Account.id(1000);


    private ExternalIdKeyFactory mockExternalIdKeyFactory;
    private Field staticField;

    @Before
    public void setUp() throws Exception {
        extIdKeyFactory = new ExternalIdKeyFactory(new ExternalIdKeyFactory.ConfigImpl(authConfig));

        mockExternalIdKeyFactory = mock(ExternalIdKeyFactory.class);

        // This is a bit of a pain because ExternalIdKeyFactory is a static injected member of WebSessionManager.Val
        // so need to do a bit of reflection here to get access and setup the mock.
        staticField = WebSessionManager.Val.class.getDeclaredField("externalIdKeyFactory");
        staticField.setAccessible(true);
        staticField.set(null, mockExternalIdKeyFactory);
        when(mockExternalIdKeyFactory.parse("external:id:key"))
                .thenReturn(extIdKeyFactory.parse("external:id:key"));
    }


    @Test
    public void testSerializationAndDeserialization() throws Exception {

        WebSessionManager.Val originalVal = new WebSessionManager.Val(
                AUTH_ACCOUNT_ID,
                1733400532803L,
                false,
                mockExternalIdKeyFactory.parse("external:id:key"),
                1733440132803L,
                "aSgeprtBG.9DBignwPlrMzVicobQMGJswW",
                "aSgeprqusRAgwRrBq-drXyOS2Pqk3mrHVW"
        );

        // Create Gson instance with the custom type adapter
        Gson gson = new GsonBuilder()
                .registerTypeAdapter(WebSessionManager.Val.class, new WebSessionValTypeAdapter())
                .create();

        // Serialize the Val object to JSON
        String json = gson.toJson(originalVal, WebSessionManager.Val.class);
        System.out.println("Serialized JSON: " + json);

        // Deserialize the JSON back to a Val object
        WebSessionManager.Val deserializedVal = gson.fromJson(json, WebSessionManager.Val.class);

        // Assert that the original and deserialized objects are equal
        assertEquals(originalVal.getAccountId(), deserializedVal.getAccountId());
        assertEquals(originalVal.isPersistentCookie(), deserializedVal.isPersistentCookie());
        assertEquals(originalVal.getExternalId(), deserializedVal.getExternalId());
        assertEquals(originalVal.getExpiresAt(), deserializedVal.getExpiresAt());
        assertEquals(originalVal.getSessionId(), deserializedVal.getSessionId());
        assertEquals(originalVal.getAuth(), deserializedVal.getAuth());
    }
}
