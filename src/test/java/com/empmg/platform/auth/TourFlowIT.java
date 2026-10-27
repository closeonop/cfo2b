package com.empmg.platform.auth;

import com.empmg.platform.BaseIT;
import com.empmg.platform.security.AccessRole;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.Executors;
import java.util.concurrent.Callable;
import static org.junit.jupiter.api.Assertions.*;

class TourFlowIT extends BaseIT {
    @Test void completionIsPersistentIdempotentAndInTheSession() throws Exception {
        var c=onboard("Tour persistence");
        assertEquals(0,getJson(c,"/api/auth/me").path("user").path("toursCompleted").size());
        assertEquals(0,getJson(c,"/api/user/tours").size());
        assertEquals("roles",apiPost(c,"/api/user/tours/roles/complete",null).path(0).asText());
        assertEquals(1,apiPost(c,"/api/user/tours/roles/complete",null).size());
        var fresh=loginAs(c.email(),PASSWORD);
        assertEquals("roles",getJson(fresh,"/api/auth/me").path("user").path("toursCompleted").path(0).asText());
    }
    @Test void onlyTheAuthenticatedUserCanBeUpdatedAcrossAccountsAndCompanies() throws Exception {
        var c=onboard("Tour owner");var colleague=createUser(c,AccessRole.HR,null);var other=onboard("Tour other company");
        apiPost(c,"/api/user/tours/dashboard/complete",Map.of("userId",999999,"companyId",other.companyId()));
        assertEquals(1,getJson(c,"/api/user/tours").size());
        assertEquals(0,getJson(colleague,"/api/user/tours").size());
        assertEquals(0,getJson(other,"/api/user/tours").size());
        assertTrue(statusOf(null,"POST","/api/user/tours/roles/complete",null)>=400);
        assertTrue(statusOf(null,"GET","/api/user/tours",null)>=400);
    }
    @Test void unknownKeysCannotGrowOrInjectThePreferenceArray() throws Exception {
        var c=onboard("Tour validation");
        assertEquals(400,statusOf(c,"POST","/api/user/tours/not-a-real-page/complete",null));
        assertEquals(400,statusOf(c,"POST","/api/user/tours/roles-admin-override/complete",null));
        assertEquals(0,getJson(c,"/api/user/tours").size());
        assertEquals(1,apiPost(c,"/api/user/tours/portal-task/complete",null).size());
    }
    @Test void concurrentCompletionsDoNotOverwriteEachOtherOrDuplicateKeys() throws Exception {
        var c=onboard("Tour concurrency");
        var keys=List.of("dashboard","employees","roles","reports","portal-task","dashboard","roles");
        try(var pool=Executors.newFixedThreadPool(4)) {
            var calls=keys.stream().map(key->(Callable<Void>)()->{apiPost(c,"/api/user/tours/"+key+"/complete",null);return null;}).toList();
            for(var result:pool.invokeAll(calls))result.get();
        }
        var saved=getJson(c,"/api/user/tours");var unique=new HashSet<String>();saved.forEach(value->unique.add(value.asText()));
        assertEquals(5,saved.size());assertEquals(new HashSet<>(keys),unique);
    }
}
