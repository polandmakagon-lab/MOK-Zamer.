package ru.mok.zamer;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasAction;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;
import static org.junit.Assert.assertEquals;

@RunWith(AndroidJUnit4.class)
public class VisitRouteTest {
    @Before public void interceptMapLinks() {
        Intents.init();
        intending(hasAction(Intent.ACTION_VIEW))
                .respondWith(new Instrumentation.ActivityResult(Activity.RESULT_OK, null));
    }

    @After public void releaseIntents() {
        Intents.release();
    }

    private List<Uri> mapLinks() {
        List<Uri> links = new ArrayList<>();
        for (Intent intent : Intents.getIntents()) {
            if (Intent.ACTION_VIEW.equals(intent.getAction())) links.add(intent.getData());
        }
        return links;
    }

    @Test public void routeFollowsSelectedVisitBeforeAndAfterScheduling() throws Exception {
        JSONObject first = new JSONObject().put("name", "Тест А").put("address", "Первый адрес")
                .put("lat", 45.0355).put("lon", 38.9753);
        JSONObject second = new JSONObject().put("name", "Тест Б").put("address", "Второй адрес")
                .put("lat", 45.2158).put("lon", 39.2265).put("appointment", 1700000000);
        try (ActivityScenario<VisitsActivity> screen = ActivityScenario.launch(VisitsActivity.class)) {
            screen.onActivity(activity -> activity.details(first));
            onView(withText("Проложить маршрут")).perform(click());
            screen.onActivity(activity -> activity.details(second));
            onView(withText("Проложить маршрут")).perform(click());

            List<Uri> links = mapLinks();
            assertEquals(2, links.size());
            assertEquals("~45.0355,38.9753", links.get(0).getQueryParameter("rtext"));
            assertEquals("~45.2158,39.2265", links.get(1).getQueryParameter("rtext"));
            for (Uri link : links) {
                assertEquals("https", link.getScheme());
                assertEquals("yandex.ru", link.getHost());
                assertEquals("/maps/", link.getPath());
                assertEquals("auto", link.getQueryParameter("rtt"));
            }
        }
    }

    @Test public void absentCoordinatesDoNotOpenAnUnrelatedPoint() throws Exception {
        JSONObject job = new JSONObject().put("name", "Тест").put("address", "Тестовый адрес");
        try (ActivityScenario<VisitsActivity> screen = ActivityScenario.launch(VisitsActivity.class)) {
            screen.onActivity(activity -> activity.details(job));
            onView(withText("Проложить маршрут")).perform(click());
            onView(withText("У этой заявки нет координат для маршрута")).check(matches(isDisplayed()));
            assertEquals(0, mapLinks().size());
        }
    }
}
