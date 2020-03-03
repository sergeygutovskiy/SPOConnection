package com.example.spoconnection;


import androidx.appcompat.app.AppCompatActivity;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.os.AsyncTask;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.google.android.material.textfield.TextInputEditText;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;

import java.nio.charset.Charset;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

//import com.example.spoconnection.Functions;


public class MainActivity extends AppCompatActivity {

    final long COOKIE_LIFETIME = 100; // в минутах. На самом деле 120 минут.


    final Integer STATS_REQUEST_TIMEOUT                 = 5; // в секундах
    final Integer LOGIN_REQUEST_TIMEOUT                 = 5;
    final Integer MAIN_DATA_REQUEST_TIMEOUT             = 5;
    final Integer STUDENT_PROFILE_REQUEST_TIMEOUT       = 5;
    final Integer SCHEDULE_REQUEST_TIMEOUT              = 5;
    final Integer EXERCISES_BY_DAY_REQUEST_TIMEOUT      = 5;
    final Integer EXERCISES_BY_LESSON_REQUEST_TIMEOUT   = 5;
    final Integer VK_POSTS_REQUEST_TIMEOUT              = 5;

    // Переменные, получаемые с запросов

    // by loginRequest
    public String authCookie;
    public String studentId;

    // by getStudentMainDataRequest
    public JSONArray studentLessons;
    public JSONObject teachers;

    // by getExercisesByDay
    public JSONArray exercisesByDay;
    public JSONObject exercisesVisitsByDay;

    // by getExercisesByLesson
    public JSONArray exercisesByLesson;
    public JSONObject exercisesByLessonVisits;
    public JSONObject exercisesByLessonTeacher;
    public Integer exercisesByLessonAmount;
    public Integer exercisesByLessonVisitsAmount;

    public JSONObject readyExercisesByLesson = new JSONObject();
    public JSONObject readyExercisesByLessonVisits = new JSONObject();

    // by vk api
    public JSONObject vkWallPosts;

    // Handlers для проверки на выполнение запроса

    /*
        Изначально запросы не вызваны - NOT_CALLED
        При вызове метода get...Request - CALLED
        Если внутри запроса ошибка - FAILED (пока не сделал)
        Если возвращает пустой response - EMPTY_RESPONSE        // Эти два значения задаются в функции колбеке on...RequestCompleted()
        Если возвращает тело response - COMPLETED               //
    */
    enum RequestStatus {NOT_CALLED, CALLED, COMPLETED, TIMEOUT, EMPTY_RESPONSE}

    RequestStatus loginRequestStatus;
    RequestStatus getStudentMainDataRequestStatus;
    RequestStatus getExercisesByDayRequestStatus;
    RequestStatus getExercisesByLessonRequestStatus;
    RequestStatus getVKWallPostsRequestStatus;
    RequestStatus getStudentProfileDataRequestStatus;
    RequestStatus getScheduleRequestStatus;
    RequestStatus getStudentStatsRequestStatus;


    // Переменная, чтобы buildFrontend не вызвался дважды (и после getMainData и после getByDay)
//    Boolean buildFrontendCalled = false;

    Boolean nowWeekScheduleCalled = false;
    Boolean nextWeekScheduleCalled = false;

    Boolean appFirstRun = false;


    // контейнеры

    public RelativeLayout main;
    public RelativeLayout profileScreen;
    public RelativeLayout loginForm;
    public LinearLayout navigation;
    public RelativeLayout homeScreen;
    public RelativeLayout scheduleScreen;
    public RelativeLayout lessonsScreen;
    public RelativeLayout lessonsInformationScreen;
    public LinearLayout userHelpScreen;
    public LinearLayout notificationListScreen;
    public RelativeLayout loadingScreen;


    // массив расписания
    JSONObject scheduleLessons = new JSONObject();

    String studentGroup; // Y2234
    String studentFIO;
    String studentAvatarSrc;

    String statsMidMark; // 4.74
    String statsDebtsCount; // 0
    String statsPercentageOfVisits; // 91%

    SharedPreferences preferences;
    SharedPreferences.Editor preferencesEditor;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // убрать шторку сверху
        Window w = getWindow();
        w.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);

        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);

        // инициализируем экраны

        main = findViewById(R.id.main);
        profileScreen = findViewById(R.id.profileScreen);
        loginForm = findViewById(R.id.loginForm);
        navigation = findViewById(R.id.navigation);
        homeScreen = findViewById(R.id.homeScreen);
        scheduleScreen = findViewById(R.id.scheduleScreen);
        lessonsScreen = findViewById(R.id.lessonsScreen);
        lessonsInformationScreen = findViewById(R.id.lessonsInformationScreen);
        userHelpScreen = findViewById(R.id.userHelp);
        notificationListScreen = findViewById(R.id.notificationListScreen);
        loadingScreen = findViewById(R.id.loadingScreen);

        // локальные кнопки экранов

        scheduleChanges = findViewById(R.id.notificationSchedule);
        scheduleNow = findViewById(R.id.now);
        scheduleNext = findViewById(R.id.next);

        // запросы для расписания отправляются только 1 раз

        scheduleNow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!nowWeekScheduleCalled) {
                    sendGetScheduleParsingRequest("now");
                    setLoadingToList(ContainerName.SCHEDULE);
                    nowWeekScheduleCalled = true;
                } else {
                    onGetScheduleRequestCompleted("now");
                }
            }
        });

        scheduleNext.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!nextWeekScheduleCalled) {
                    sendGetScheduleParsingRequest("next");
                    setLoadingToList(ContainerName.SCHEDULE);
                    nextWeekScheduleCalled = true;
                } else {
                    onGetScheduleRequestCompleted("next");
                }
            }
        });


        // в начале убираем все экраны

        main.removeView(profileScreen);
        main.removeView(navigation);
        main.removeView(homeScreen);
        main.removeView(scheduleScreen);
        main.removeView(lessonsScreen);
        main.removeView(lessonsInformationScreen);
        main.removeView(userHelpScreen);
        main.removeView(notificationListScreen);
        main.removeView(loginForm);
        activeContainer = ContainerName.LOADING;

        resetRequestsStatuses();

        preferences = MainActivity.this.getPreferences(Context.MODE_PRIVATE);

        appFirstRun = preferences.getBoolean("appFirstRun", true);
        System.out.println(preferences.getAll());

        // первый запуск
        if (appFirstRun) {

            setContainer(ContainerName.LOGIN);

            // получаем данные для отправки запроса

            final TextInputEditText login = findViewById(R.id.loginFormLogin);
            final TextInputEditText password = findViewById(R.id.loginFormPassword);
            final Button submit = findViewById(R.id.loginFormSubmit);


            submit.setOnClickListener(new View.OnClickListener() {

                // отправляем запрос
                @Override
                public void onClick(View v) {
                    sendLoginRequest(new String[] {
                            login.getText().toString(),
                            password.getText().toString()
                    });
                }
            });

            System.out.println("App first run");
//            preferencesEditor = preferences.edit();

//            preferencesEditor.putBoolean("appFirstRun", false);
//            preferencesEditor.apply();
        } else {
            System.out.println("Not app first run");
            String lastLoginRequestTime = preferences.getString("lastLoginRequest", "");

            // есть дата последнего входа в аккаунт
            if (!lastLoginRequestTime.isEmpty()) {
                Date loginRequestDate = null;
                Date currentDate;

                try { loginRequestDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").parse(lastLoginRequestTime); }
                catch (ParseException e) {}
                currentDate = new Date();

                long minutesBetweenDates = ((currentDate.getTime() / 60000) - (loginRequestDate.getTime() / 60000));
                System.out.println("Last login request was " + minutesBetweenDates + " minutes ago");

                // вход был выполнен более COOKIE_LIFETIME минут назад, тогда нужно сделать запрос заново
                if ( minutesBetweenDates >= COOKIE_LIFETIME) {

                    setContainer(ContainerName.LOGIN);

                    System.out.println("Cookie lifetime is more then " + COOKIE_LIFETIME + " minutes. Sending new login request");

                    String name = preferences.getString("studentName", "");
                    String password = preferences.getString("studentPassword", "");

                    studentGroup = preferences.getString("studentGroup", "");
                    studentFIO = preferences.getString("studentFIO", "");
                    studentAvatarSrc = preferences.getString("studentAvatarSrc", "");
                    getStudentProfileDataRequestStatus = RequestStatus.COMPLETED;

                    sendLoginRequest(new String[] { name, password });
                    // иначе пропускаем вход в аккаунт
                } else {
                    System.out.println("Cookie lifetime is less then" + COOKIE_LIFETIME + " minutes. Continue");
                    authCookie = preferences.getString("authCookie", "");
                    studentId = Functions.getStudentIdFromCookie(authCookie);

                    studentGroup = preferences.getString("studentGroup", "");
                    studentFIO = preferences.getString("studentFIO", "");
                    studentAvatarSrc = preferences.getString("studentAvatarSrc", "");

                    statsMidMark = preferences.getString("studentStatsMidMark", "");
                    statsDebtsCount = preferences.getString("studentStatsDebtsCount", "");
                    statsPercentageOfVisits = preferences.getString("studentStatsPercentageOfVisits", "");
                    getStudentProfileDataRequestStatus = RequestStatus.COMPLETED;

                    Date date = new Date();
                    String year = new SimpleDateFormat("yyyy").format(date);
                    String month = new SimpleDateFormat("MM").format(date);
                    String day = new SimpleDateFormat("dd").format(date);

                    sendGetStudentStatsRequest();
                    sendGetStudentMainDataRequest(new String[]{ year, month });
                    sendGetExercisesByDayRequest(new String[] { year + "-" + month + "-" + day }); // 2020-02-26
                }

            } else {
                setContainer(ContainerName.LOGIN);

                final TextInputEditText login = findViewById(R.id.loginFormLogin);
                final TextInputEditText password = findViewById(R.id.loginFormPassword);
                final Button submit = findViewById(R.id.loginFormSubmit);


                submit.setOnClickListener(new View.OnClickListener() {

                    // отправляем запрос
                    @Override
                    public void onClick(View v) {
                        sendLoginRequest(new String[] {
                                login.getText().toString(),
                                password.getText().toString()
                        });
                    }
                });

                System.out.println("App first login");
            }
        }


    }

    /* -------------------------------------------- BackEnd -------------------------------------------- */

    public void resetRequestsStatuses() {
        loginRequestStatus                    = RequestStatus.NOT_CALLED;
        getStudentMainDataRequestStatus       = RequestStatus.NOT_CALLED;
        getExercisesByDayRequestStatus        = RequestStatus.NOT_CALLED;
        getExercisesByLessonRequestStatus     = RequestStatus.NOT_CALLED;
        getVKWallPostsRequestStatus           = RequestStatus.NOT_CALLED;
        getStudentProfileDataRequestStatus    = RequestStatus.NOT_CALLED;
        getScheduleRequestStatus              = RequestStatus.NOT_CALLED;
        getStudentStatsRequestStatus          = RequestStatus.NOT_CALLED;
    }

    // Когда отпраили все запросы для входа в акаунт
    public void onAuthCompleted() {
        if (getStudentStatsRequestStatus == RequestStatus.COMPLETED
                && getStudentMainDataRequestStatus == RequestStatus.COMPLETED
                && getExercisesByDayRequestStatus  == RequestStatus.COMPLETED
        ) {
            preferences = MainActivity.this.getPreferences(Context.MODE_PRIVATE);
            preferencesEditor = preferences.edit();
            preferencesEditor.putBoolean("appFirstRun", false);
            preferencesEditor.apply();

            buildFrontend();
        }
    }

    public void getStudentAvatar() {

    }

    // Функции по отправке запроса. Их нужно вызывать при жедании сделать запрос

    private void sendLoginRequest(String[] params) {
        setContainer(ContainerName.LOADING);
        loginRequest request = new loginRequest();
        loginRequestStatus = RequestStatus.CALLED;
        request.execute(params);
    }

    private void sendGetStudentMainDataRequest(String[] params) {
        getStudentMainDataRequest request = new getStudentMainDataRequest();
        getStudentMainDataRequestStatus = RequestStatus.CALLED;
        request.execute(params);
    }

    private void sendGetExercisesByDayRequest(String[] params) {
        getExercisesByDayRequest request = new getExercisesByDayRequest();
        getExercisesByDayRequestStatus = RequestStatus.CALLED;
        request.execute(params);
    }

    private void sendGetExercisesByLessonRequest(String[] params) {
        getExercisesByLessonRequest request = new getExercisesByLessonRequest();
        getExercisesByLessonRequestStatus = RequestStatus.CALLED;
        request.execute(params);
    }

    private void sendGetVKWallPostsRequest(String[] params) {
        getVKWallPostsRequest request = new getVKWallPostsRequest();
        getVKWallPostsRequestStatus = RequestStatus.CALLED;
        request.execute(params);
    }

    private void sendGetStudentProfileDataRequest() {
        getStudentProfileDataRequest request = new getStudentProfileDataRequest();
        getStudentProfileDataRequestStatus = RequestStatus.CALLED;
        request.execute();
    }

    private void sendGetScheduleParsingRequest(String param) {
        getScheduleRequest request = new getScheduleRequest();
        getScheduleRequestStatus = RequestStatus.CALLED;
        request.execute(new String[] {param});
    }

    private void sendGetStudentStatsRequest() {
        getStudentStatsRequest request = new getStudentStatsRequest();
        getStudentStatsRequestStatus = RequestStatus.CALLED;
        request.execute();
    }


    // Колбеки, которые вызываются при завершении определенного запроса

    public void onLoginRequestCompleted(String[] response) {
        String cookie = response[0];
        String studentName = response[1];
        String studentPassword = response[2];

        if (loginRequestStatus == RequestStatus.TIMEOUT) {

        } else if (!cookie.isEmpty()) {
            loginRequestStatus = RequestStatus.COMPLETED;

            authCookie = cookie;
            studentId = Functions.getStudentIdFromCookie(authCookie);

            System.out.println("Login success!");
            System.out.println("AuthCookie: " + authCookie);

            preferences = MainActivity.this.getPreferences(Context.MODE_PRIVATE);
            preferencesEditor = preferences.edit();

            String currentDate = new SimpleDateFormat("dd-MM-yyyy HH:mm:ss").format(new Date());
            preferencesEditor.putString("lastLoginRequest", currentDate);
            preferencesEditor.putString("authCookie", authCookie);
            preferencesEditor.putString("studentName", studentName);
            preferencesEditor.putString("studentPassword", studentPassword);
            preferencesEditor.apply();

            // После входа в акк загружаем предметы, учителей и пары за сегодня

            Date date = new Date();
            String year = new SimpleDateFormat("yyyy").format(date);
            String month = new SimpleDateFormat("MM").format(date);
            String day = new SimpleDateFormat("dd").format(date);

            sendGetStudentStatsRequest();
            sendGetStudentMainDataRequest(new String[]{ year, month });
            if (getStudentProfileDataRequestStatus != RequestStatus.COMPLETED) sendGetStudentProfileDataRequest();
            sendGetExercisesByDayRequest(new String[] { year + "-" + month + "-" + day }); // 2020-02-26

        } else {
            loginRequestStatus = RequestStatus.EMPTY_RESPONSE;
            System.out.println("Login request empty response!");
        }
    }

    public void onGetStudentMainDataRequestCompleted(String responseBody) {

        if (getStudentMainDataRequestStatus == RequestStatus.TIMEOUT) {

        } else if (!responseBody.isEmpty()) {
            getStudentMainDataRequestStatus = RequestStatus.COMPLETED;

            System.out.println("GetStudentMainData Success!");
            JSONObject jsonData;
            try {
                jsonData = new JSONObject(responseBody);

                studentLessons = jsonData.getJSONArray("userlessons");
                teachers = jsonData.getJSONObject("lessonteachers");

//                System.out.println("StudentLessons: " + studentLessons.toString());
//                System.out.println("Teachers: " + teachers.toString());

//                if (getExercisesByDayRequestStatus == RequestStatus.COMPLETED
//                        && getStudentProfileDataRequestStatus == RequestStatus.COMPLETED
//                        && !buildFrontendCalled)
//                {
//                    buildFrontendCalled = true;
//                    buildFrontend();
//                }

            } catch (JSONException e) {

            }
        } else {
            getStudentMainDataRequestStatus = RequestStatus.EMPTY_RESPONSE;
            System.out.println("Student main data request empty response!");
        }
    }

    public void onGetExercisesByDayRequestCompleted (String responseBody) {

        if (getExercisesByDayRequestStatus == RequestStatus.TIMEOUT) {

        } else if (!responseBody.isEmpty()) {
            getExercisesByDayRequestStatus = RequestStatus.COMPLETED;

            System.out.println("GetExercisesByDay Success!");
            JSONObject jsonData;
            try {
                jsonData = new JSONObject(responseBody);

                exercisesByDay = jsonData.getJSONArray("todayExercises");

//                System.out.println(jsonData.get("todayExercisesVisits"));
//                System.out.println(jsonData.toString());

                if (!jsonData.get("todayExercisesVisits").equals(null))
                    exercisesVisitsByDay = jsonData.getJSONObject("todayExercisesVisits");
                else
                    exercisesVisitsByDay = new JSONObject();
//                System.out.println("TodayExercises: " + exercisesByDay.toString());
//                System.out.println("TodayExercisesVisits: " + exercisesVisitsByDay.toString());
//
//                if (getStudentMainDataRequestStatus == RequestStatus.COMPLETED
//                        && getStudentProfileDataRequestStatus == RequestStatus.COMPLETED
//                        && !buildFrontendCalled)
//                {
//                    buildFrontendCalled = true;
//                    buildFrontend();
//                }
//
//                buildFrontend();

                onAuthCompleted();

            } catch (JSONException e) {
                System.out.println(e.toString());
            }
        } else {
            getExercisesByDayRequestStatus = RequestStatus.EMPTY_RESPONSE;
            System.out.println("Exercises by day request empty response!");
        }
    }

    public void onGetExercisesByLessonRequestCompleted (String[] response) {

        String responseBody = response[0];
        String lessonId = response[1];

        if (getExercisesByLessonRequestStatus == RequestStatus.TIMEOUT) {

        } else if (!responseBody.isEmpty() && activeContainer == ContainerName.LESSONS_INFORMATION) {
            getExercisesByLessonRequestStatus = RequestStatus.COMPLETED;

            System.out.println("GetExercisesByLesson Success!");
            JSONObject jsonData;
            try {
                jsonData = new JSONObject(responseBody);

                exercisesByLesson = jsonData.getJSONArray("Exercises");
                exercisesByLessonVisits = jsonData.getJSONObject("todayExercisesVisits");
                exercisesByLessonTeacher = jsonData.getJSONObject("teacher");
                exercisesByLessonAmount = jsonData.getInt("all");
                exercisesByLessonVisitsAmount = jsonData.getInt("was");

//                System.out.println("exercisesByLesson: " + exercisesByLesson.toString());
//                System.out.println("exercisesByLessonVisits: " + exercisesByLessonVisits.toString());
//                System.out.println("exercisesByLessonTeacher: " + exercisesByLessonTeacher.toString());
//                System.out.println("exercisesByLessonAmount: " + exercisesByLessonAmount.toString());
//                System.out.println("exercisesByLessonVisitsAmount: " + exercisesByLessonVisitsAmount.toString());
            } catch (JSONException e) {

            }

            // берем нужный предмет
            JSONArray buffer = exercisesByLesson;
            LinearLayout lessonsInformationList = findViewById(R.id.lessonsInformationList);
            lessonsInformationList.removeAllViews();

            // выкидываем информацию о паре
            for (int k = 0; k < buffer.length(); k++) {
                JSONObject value;
                try {

                    value = buffer.getJSONObject(k);
                    TextView temp = new TextView(getApplicationContext());
                    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150);
                    lp.setMargins(0,0,0, 50);
                    temp.setLayoutParams(lp);
                    temp.setText(value.getString("topic") + " и эта пара была " + value.getString("day"));
                    temp.setBackgroundColor(167);


                    // получаем подробную информацию о паре

                    JSONObject valueInfo;
                    try {
                        valueInfo = exercisesByLessonVisits.getJSONArray(value.getString("id")).getJSONObject(0);

                        String presence = valueInfo.getString("presence").equals("0") ? " присутствие: нет " : " присутствие: да ";
                        String point = valueInfo.getString("point").toString().equals("null")  ? " оценка: нет " : " оценка: да ";
                        switch (valueInfo.getString("point")) {
                            case "2": {
                                point = " оценка: 2";
                                break;
                            }
                            case "3": {
                                point = " оценка: 3";
                                break;
                            }
                            case "4": {
                                point = " оценка: 4";
                                break;
                            }
                            case "5": {
                                point = " оценка: 5";
                                break;
                            }
                        }
                        String delay = valueInfo.getString("delay").toString().equals("null")  ? " опоздание: нет " : " опоздание: да ";
                        String performance = valueInfo.getString("performance").toString().equals("null") ? " активность: нет " : " активность: да ";

                        temp.setText(temp.getText() + presence + point + delay + performance);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }




                    // опять же id - ключ для следующего массива

                    temp.setId(Integer.parseInt(value.getString("id")));
                    lessonsInformationList.addView(temp);

                } catch (JSONException e) {
                    e.printStackTrace();
                }
            }

            try {
                readyExercisesByLesson.put(lessonId, exercisesByLesson);
                readyExercisesByLessonVisits.put(lessonId, exercisesByLessonVisits);
            } catch (JSONException e) {
                e.printStackTrace();
            }

            System.out.println("readyExercisesByLesson: "  + readyExercisesByLesson.toString());
            System.out.println("readyExercisesByLessonVisits: "  + readyExercisesByLessonVisits.toString());

        } else {
            getExercisesByLessonRequestStatus = RequestStatus.EMPTY_RESPONSE;
            System.out.println("Exercises by lesson request empty response!");
        }
    }

    public void onGetVKWallPostsRequestCompleted (String responseBody) {

        if (getVKWallPostsRequestStatus == RequestStatus.TIMEOUT) {

        } else if (!responseBody.isEmpty() && activeContainer == ContainerName.NOTIFICATION) {
            getVKWallPostsRequestStatus = RequestStatus.COMPLETED;

            System.out.println("GetVKWallPosts Success!");
            JSONObject jsonData;
            try {
                jsonData = new JSONObject(responseBody);

                vkWallPosts = jsonData.getJSONObject("response");

                System.out.println("vkWallPosts: " + vkWallPosts.toString());

            } catch (JSONException e) {

            }

            JSONArray value;
            try {
                value = vkWallPosts.getJSONArray("items");
                LinearLayout notificationList = findViewById(R.id.notificationList);
                notificationList.removeAllViews();

                for (int i = 0; i < value.length(); i++) {

                    // берем каждый пост

                    JSONObject tmp;
                    try {
                        tmp = value.getJSONObject(i);

                        //и выкидывем его на форму
                        long stamp = System.currentTimeMillis()/1000;
                        System.out.println("current time: " + stamp);

                        //и выкидывем его на форму если он моложе двух дней
                        if (stamp - Long.parseLong(tmp.getString("date")) <= 2*24*3600) {

                            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                            lp.setMargins(25, 25, 25, 50);
                            TextView note = new TextView(getApplicationContext());
                            note.setLayoutParams(lp);
                            note.setText( (i+1) + " пост (" + new Date(Long.parseLong(tmp.getString("date"))*1000) + "):    " + tmp.getString("text"));
                            notificationList.addView(note);
                        }

                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }

            } catch (JSONException e) {
                e.printStackTrace();
            }

        } else {
            getVKWallPostsRequestStatus = RequestStatus.EMPTY_RESPONSE;
            System.out.println("VK wall posts request empty response!");
        }
    }

    public void onGetStudentProfileDataRequestCompleted (String[] response){

        String studentFIO = response[0];
        String studentGroup = response[1];
        String studentAvatarSrc = response[2];

        if (getStudentProfileDataRequestStatus == RequestStatus.TIMEOUT) {

        } else if ( !(studentFIO.isEmpty() || studentGroup.isEmpty() || studentAvatarSrc.isEmpty()) ) {
            getStudentProfileDataRequestStatus = RequestStatus.COMPLETED;

            preferences = MainActivity.this.getPreferences(Context.MODE_PRIVATE);
            preferencesEditor = preferences.edit();

            preferencesEditor.putString("studentFIO", studentFIO);
            preferencesEditor.putString("studentGroup", studentGroup);
            preferencesEditor.putString("studentAvatarSrc", studentAvatarSrc);

            preferencesEditor.putString("studentStatsMidMark", statsMidMark);
            preferencesEditor.putString("studentStatsDebtsCount", statsDebtsCount);
            preferencesEditor.putString("studentStatsPercentageOfVisits", statsPercentageOfVisits);

            preferencesEditor.apply();

            this.studentFIO = studentFIO;
            this.studentGroup = studentGroup;
            this.studentAvatarSrc = studentAvatarSrc;

//            if (getExercisesByDayRequestStatus == RequestStatus.COMPLETED && getStudentMainDataRequestStatus == RequestStatus.COMPLETED && !buildFrontendCalled) {
//                buildFrontendCalled = true;
//                buildFrontend();
//            }
        } else {
            getStudentProfileDataRequestStatus = RequestStatus.EMPTY_RESPONSE;
            System.out.println("Student profile's data request empty response!");
        }
    }

    public void onGetScheduleRequestCompleted(String param) {

        if (getScheduleRequestStatus == RequestStatus.TIMEOUT) {

        } else if (getScheduleRequestStatus == RequestStatus.COMPLETED && activeContainer == ContainerName.SCHEDULE) {

            LinearLayout box = findViewById(R.id.scheduleList);
            box.removeAllViews();
            TextView text = new TextView(getApplicationContext());

            JSONArray value = new JSONArray();

            try {
                value = scheduleLessons.getJSONArray(param);
            } catch (JSONException e) {
                e.printStackTrace();
            }

            for (int i = 0; i < value.length(); i++) {

                switch (i) {
                    case 0: {
                        text.setText(text.getText() + "Понедельник:\n\n");
                        break;
                    }
                    case 1: {
                        text.setText(text.getText() + "Вторник:\n\n");
                        break;
                    }
                    case 2: {
                        text.setText(text.getText() + "Среда:\n\n");
                        break;
                    }
                    case 3: {
                        text.setText(text.getText() + "Четверг:\n\n");
                        break;
                    }
                    case 4: {
                        text.setText(text.getText() + "Пятница:\n\n");
                        break;
                    }
                    case 5: {
                        text.setText(text.getText() + "Суббота:\n\n");
                        break;
                    }
                }

                JSONArray temp = new JSONArray();
                try {
                    temp = value.getJSONArray(i);
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                for (int j = 0; j < temp.length(); j++) {

                    JSONObject tmp = new JSONObject();
                    try {
                        tmp = temp.getJSONObject(j);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                    try {
                        text.setText(text.getText() + tmp.getString("position") + " (" + tmp.getString("start") + tmp.getString("end") + ") " + tmp.getString("name") + " (" + tmp.getString("teacher") + ")\n");
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                    text.setText(text.getText() + "\n\n");

                }
            }

            box.addView(text);

        } else {
            getScheduleRequestStatus = RequestStatus.EMPTY_RESPONSE;
            System.out.println("Schedule request empty response!");
        }
    }

    public void onGetStudentStatsRequestCompleted(String[] response) {

        String statsMidMark = response[0];
        String statsDebtsCount = response[1];
        String statsPercentageOfVisits = response[2];

        if (getStudentStatsRequestStatus == RequestStatus.TIMEOUT) {

        } else if ( !(statsDebtsCount.isEmpty() || statsDebtsCount.isEmpty() || statsPercentageOfVisits.isEmpty()) ) {
            getStudentStatsRequestStatus = RequestStatus.COMPLETED;

            System.out.println("StudentStats Success!");

            this.statsMidMark = statsMidMark;
            this.statsDebtsCount = statsDebtsCount;
            this.statsPercentageOfVisits = statsPercentageOfVisits;
        } else {
            getStudentStatsRequestStatus = RequestStatus.EMPTY_RESPONSE;
            System.out.println("Student stats request empty response!");
        }
    }

    // Сами асинхронные запросы

    // [name, password]
    class loginRequest extends AsyncTask<String[], Void, String[]> {

        @Override
        protected String[] doInBackground(String[]... params) { // params[0][0] - name, params[0][1] - password
            URL url;
            HttpURLConnection urlConnection = null;
            String cookie = "";

            try {
                String url_address = "https://ifspo.ifmo.ru/";
                url = new URL(url_address);
                urlConnection = (HttpURLConnection) url.openConnection();

                String urlParameters = "User[login]=" + params[0][0] + "&User[password]=" + params[0][1];
                byte[] postData = urlParameters.getBytes(Charset.forName("UTF-8"));
                int postDataLength = postData.length;

                urlConnection.setRequestMethod("POST");
                urlConnection.setReadTimeout(LOGIN_REQUEST_TIMEOUT * 1000);

                urlConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                urlConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.3; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.130 Safari/537.36 OPR/66.0.3515.95");
                urlConnection.setRequestProperty("Content-Length", Integer.toString(postDataLength));
                urlConnection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9");
                urlConnection.setDoOutput(true);
                urlConnection.setUseCaches(false);
                urlConnection.setInstanceFollowRedirects(false);

                try (DataOutputStream wr = new DataOutputStream(urlConnection.getOutputStream())) {
                    wr.write(postData);
                }

                List<String> cookies = urlConnection.getHeaderFields().get("Set-cookie");
                Integer cookies_count = cookies.size();

                if (cookies_count > 1) {
                    cookie = cookies.get(cookies_count - 1);
                }
            } catch (SocketTimeoutException e) {
                System.out.println("Login request timeout!");
                loginRequestStatus = RequestStatus.TIMEOUT;
                return new String[] {"", "", ""};
            } catch (Exception e) {
                System.out.println("Problems with login request");
                System.out.println(e.toString());
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }

            return new String[] { cookie, params[0][0], params[0][1] };
        }

        @Override
        protected void onPostExecute(String[] result) {
            super.onPostExecute(result);
            onLoginRequestCompleted(result);
        }
    }

    // [year, month]
    class getStudentMainDataRequest extends AsyncTask<String[], Void, String> {

        protected String doInBackground(String[]... params) { // params[0][0] - year, params[0][1] - month

            System.out.println("Student main data request called");

            HttpURLConnection urlConnection = null;
            String responseBody = "";


            // создаем мап для картинки
            if (getStudentProfileDataRequestStatus == RequestStatus.COMPLETED) {
                try {
                    bitmap = BitmapFactory.decodeStream((InputStream) new URL("https://ifspo.ifmo.ru" + studentAvatarSrc).getContent());
                } catch (MalformedURLException e) {
                    e.printStackTrace();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }

            try {
                String url_address = "https://ifspo.ifmo.ru/profile/getStudentLessonsVisits"
                        + "?stud=" + studentId
                        + "&dateyear=" + params[0][0]
                        + "&datemonth=" + params[0][1];

                urlConnection = Functions.setupGetAuthRequest(url_address, authCookie, MAIN_DATA_REQUEST_TIMEOUT);
                responseBody = Functions.getResponseFromGetRequest(urlConnection);

            } catch (SocketTimeoutException e) {
                System.out.println("Main data request timeout!");
                getStudentMainDataRequestStatus = RequestStatus.TIMEOUT;
                return "";
            } catch (Exception e) {
                System.out.println("Problems with getStudentMainData request");
                System.out.println(e.toString());
            } finally {
                if (urlConnection != null) urlConnection.disconnect();
            }
            return responseBody;
        }

        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            onGetStudentMainDataRequestCompleted(result);
        }
    }

    // [lessonId]
    class getExercisesByLessonRequest extends AsyncTask <String[], Void, String[]> {

        protected String[] doInBackground(String[]... params) { // params[0][0] - lesson_id (String)
            HttpURLConnection urlConnection = null;
            String responseBody = "";

            try {
                String url_address = "https://ifspo.ifmo.ru/journal/getStudentExercisesByLesson"
                        + "?lesson=" + params[0][0]
                        + "&student=" + studentId;

                urlConnection = Functions.setupGetAuthRequest(url_address, authCookie, EXERCISES_BY_LESSON_REQUEST_TIMEOUT);
                responseBody = Functions.getResponseFromGetRequest(urlConnection);

            } catch (SocketTimeoutException e) {
                System.out.println("Exercises by lesson request timeout!");
                getExercisesByLessonRequestStatus = RequestStatus.TIMEOUT;
                return new String[] {"", ""};
            } catch (Exception e) {
                System.out.println("Problems with getStudentMainData request");
                System.out.println(e.toString());
            } finally {
                if (urlConnection != null) urlConnection.disconnect();
            }
            return new String[] {responseBody, params[0][0]};
        }

        protected void onPostExecute(String[] result) {
            super.onPostExecute(result);
            onGetExercisesByLessonRequestCompleted(result);
        }
    }

    // [date <yyyy-mm-dd>]
    class getExercisesByDayRequest extends AsyncTask <String[], Void, String> {

        protected String doInBackground(String[]... params) { // params[0][0] - date <yyyy-mm-dd>
            HttpURLConnection urlConnection = null;
            String responseBody = "";

            try {
                String url_address = "https://ifspo.ifmo.ru//journal/getStudentExercisesByDay"
                        + "?student=" + studentId
                        + "&day=" + params[0][0];

                urlConnection = Functions.setupGetAuthRequest(url_address, authCookie, EXERCISES_BY_DAY_REQUEST_TIMEOUT);
                responseBody = Functions.getResponseFromGetRequest(urlConnection);

            } catch (SocketTimeoutException e) {
                System.out.println("Exercises by day request timeout!");
                getExercisesByDayRequestStatus = RequestStatus.TIMEOUT;
                return "";
            } catch (Exception e) {
                System.out.println("Problems with getStudentMainData request");
                System.out.println(e.toString());
            } finally {
                if (urlConnection != null) urlConnection.disconnect();
            }
            return responseBody;
        }

        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            onGetExercisesByDayRequestCompleted(result);
        }
    }

    // [postsCount]
    class getVKWallPostsRequest extends AsyncTask <String[], Void, String> { // params[0][0] - posts count

        protected String doInBackground(String[]... params) {
            HttpURLConnection urlConnection = null;
            String responseBody = "";

            try {
                String url_address = "https://api.vk.com/method/wall.get?domain=raspfspo"
                        + "&count=" + params[0][0]
                        + "&filter=owner&access_token=c2cb19e3c2cb19e3c2cb19e339c2a4f3d6cc2cbc2cb19e39c9fe125dc37c9d4bb7994cd&v=5.103";

                urlConnection = Functions.setupGetAuthRequest(url_address, authCookie, VK_POSTS_REQUEST_TIMEOUT);
                responseBody = Functions.getResponseFromGetRequest(urlConnection);

            } catch (SocketTimeoutException e) {
                System.out.println("VK posts request timeout!");
                getVKWallPostsRequestStatus = RequestStatus.TIMEOUT;
                return "";
            } catch (Exception e) {
                System.out.println("Problems with getStudentMainData request");
                System.out.println(e.toString());
            } finally {
                if (urlConnection != null) urlConnection.disconnect();
            }
            return responseBody;
        }

        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            onGetVKWallPostsRequestCompleted(result);
        }
    }

    Bitmap bitmap; // картинка профиля

    class getStudentProfileDataRequest extends AsyncTask<Void, Void, String[]> { //FIO, GROUP

        protected String[] doInBackground(Void... params) {
            HttpURLConnection urlConnection = null;
            String responseBody = "";
            Document html = new Document(responseBody);

            try {
                String url_address = "https://ifspo.ifmo.ru/profile";

                urlConnection = Functions.setupGetAuthRequest(url_address, authCookie, STUDENT_PROFILE_REQUEST_TIMEOUT);
                responseBody = Functions.getResponseFromGetRequest(urlConnection);

                html = Jsoup.parse(responseBody);

            } catch (SocketTimeoutException e) {
                System.out.println("Student profile data request timeout!");
                getStudentProfileDataRequestStatus = RequestStatus.TIMEOUT;
                return new String[] {"", "", ""};
            } catch (Exception e) {
                System.out.println("Problems with GetProfileParsing request");
                System.out.println(e.toString());
            } finally {
                if (urlConnection != null) urlConnection.disconnect();
            }

            System.out.println("GetProfileParsing Success!");

            String studentFIO = "";
            String studentGroup = "";
            String avatarSrc = "";

            String statsMidMark = "";
            String statsDebtsCount = "";
            String statsPercentageOfVisits = "";

            Element row;

//            Integer bodyRowsCount = html.body().getElementsByClass("row").size();
//            if (bodyRowsCount > 1) {
//                row = html.body().children().getElementsByClass("row").get(bodyRowsCount - 1);
//            } else {
//                row = html.body()getElementsByClass("row").get(0);
//            }

            row = html.body().getElementsByClass("row").get(2);

//            System.out.println(row.toString());

            studentFIO = row.getElementsByClass("span9").select("h3").get(0).text();
            studentGroup = row.getElementsByClass("span9").get(0)
                    .getElementsByClass("row").get(0)
                    .getElementsByClass("span3").select("ul").select("li").last().text();
            avatarSrc = row.getElementsByClass("span3").get(0)
                    .getElementsByClass("showchange").get(0)
                    .getElementsByTag("img").get(0).attr("src");

            studentGroup = studentGroup.split(" ")[0];

            // создаем мап для картинки

            try {
                bitmap = BitmapFactory.decodeStream((InputStream)new URL("https://ifspo.ifmo.ru" + avatarSrc).getContent());
            } catch (MalformedURLException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

            return new String[] { studentFIO, studentGroup, avatarSrc };
        }

        protected void onPostExecute(String[] result) {
            super.onPostExecute(result);
            onGetStudentProfileDataRequestCompleted(result);

        }
    }

    class getScheduleRequest extends AsyncTask<String[], Void, String> { // Void на самом деле

        protected String doInBackground(String[]... params) { // now next
            URL url;
            HttpURLConnection urlConnection = null;
            String responseBody = "";
            Document html = new Document(responseBody);

            try {
                String url_address = "https://ifspo.ifmo.ru/schedule/get?num=" + Functions.getGroupIdByName(studentGroup) + "&week=" + params[0][0];

                url = new URL(url_address);
                urlConnection = (HttpURLConnection) url.openConnection();

                urlConnection.setRequestMethod("GET");
                urlConnection.setReadTimeout(SCHEDULE_REQUEST_TIMEOUT * 1000);

                urlConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.3; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.130 Safari/537.36 OPR/66.0.3515.95");
                urlConnection.setRequestProperty("Cookie", authCookie);
                urlConnection.setUseCaches(false);
                urlConnection.setInstanceFollowRedirects(false);

                BufferedReader in = new BufferedReader(
                        new InputStreamReader(urlConnection.getInputStream())
                );

                StringBuilder response = new StringBuilder();
                String currentLine;

                try {
                    while ((currentLine = in.readLine()) != null) response.append(currentLine);
                    in.close();
                } catch (IOException e) {
                    System.out.println(e.toString());
                }

                responseBody = response.toString();
                html = Jsoup.parse(responseBody);
            } catch (SocketTimeoutException e) {
                System.out.println("Schedule request timeout!");
                getScheduleRequestStatus = RequestStatus.TIMEOUT;
                return "";
            } catch (Exception e) {
                System.out.println("Problems with scheduleParsing request");
                System.out.println(e.toString());
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }

            System.out.println("GetScheduleParsing Success!");

            JSONArray scheduleRoot = new JSONArray();

            try {
                scheduleLessons.put(params[0][0], new JSONArray());
//                System.out.println(scheduleLessons.toString());
                scheduleRoot = scheduleLessons.getJSONArray(params[0][0]);

            } catch (JSONException e) {
                e.printStackTrace();
            }

            for (int i = 0; i < 6; i++) {

                scheduleRoot.put(new JSONArray());

                JSONArray scheduleRootLesson = new JSONArray();
                try {
                    scheduleRootLesson = scheduleRoot.getJSONArray(i);
                } catch (JSONException e) {
                    e.printStackTrace();
                }

                Element root = html.body().getElementsByClass("schedule-row-table").get(0).getElementsByClass("weekday-div").get(i);
                Elements lessonRoot = root.getElementsByClass("period-tr");
                for (Element elem : lessonRoot) {

                    scheduleRootLesson.put(new JSONObject());

                    String numberOfLesson = elem.select("div").get(0).text();
                    numberOfLesson = numberOfLesson.split(" ")[0];

                    String timeOfLessonStart = "";
                    String timeOfLessonEnd = "";

                    switch (numberOfLesson) {
                        case "I": {
                            timeOfLessonStart = "8:20";
                            timeOfLessonEnd = "9:50";
                            break;
                        }
                        case "II": {
                            timeOfLessonStart = "10:00";
                            timeOfLessonEnd = "11:30";
                            break;
                        }
                        case "III": {
                            timeOfLessonStart = "11:40";
                            timeOfLessonEnd = "13:10";
                            break;
                        }
                        case "IV": {
                            timeOfLessonStart = "13:30";
                            timeOfLessonEnd = "15:00";
                            break;
                        }
                        case "V": {
                            timeOfLessonStart = "15:20";
                            timeOfLessonEnd = "16:50";
                            break;
                        }
                        case "VI": {
                            timeOfLessonStart = "17:00";
                            timeOfLessonEnd = "18:30";
                            break;
                        }
                    }
                    String nameOfLesson = elem.getElementsByClass("lesson_td").get(0).select("div").get(0).text();
                    String teacherOfLesson = elem.getElementsByClass("lesson_td").get(0).select("div").get(1).text();

                    JSONObject scheduleRootLessonInformation = new JSONObject();
                    try {
                        scheduleRootLessonInformation = scheduleRootLesson.getJSONObject(scheduleRootLesson.length() - 1);
                        scheduleRootLessonInformation.put("position", numberOfLesson);
                        scheduleRootLessonInformation.put("start", timeOfLessonStart);
                        scheduleRootLessonInformation.put("end", timeOfLessonEnd);
                        scheduleRootLessonInformation.put("name", nameOfLesson);
                        scheduleRootLessonInformation.put("teacher", teacherOfLesson);
                    } catch (JSONException e) {
                        e.printStackTrace();
                    }

                }

            }

            System.out.println(scheduleLessons.toString());

            return params[0][0];
        }

        protected void onPostExecute(String result) {
            super.onPostExecute(result);
            getScheduleRequestStatus = RequestStatus.COMPLETED;
            onGetScheduleRequestCompleted(result);
        }
    }

    class getStudentStatsRequest extends AsyncTask<Void, Void, String[]> { // Void на самом деле

        protected String[] doInBackground(Void ...params) {
            String statsMidMark = "";
            String statsDebtsCount = "";
            String statsPercentageOfVisits = "";

            String responseBody = "";
            Document html;

            HttpURLConnection urlConnection = null;

            try {
                String url_address = "https://ifspo.ifmo.ru/profile/getStudentStatistics";
                URL url = new URL(url_address);
                urlConnection = (HttpURLConnection) url.openConnection();

                String urlParameters = "student_id=" + studentId;
                byte[] postData = urlParameters.getBytes(Charset.forName("UTF-8"));
                int postDataLength = postData.length;

                urlConnection.setRequestMethod("POST");
                urlConnection.setReadTimeout(STATS_REQUEST_TIMEOUT * 1000);

                urlConnection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8");
                urlConnection.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 6.3; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/79.0.3945.130 Safari/537.36 OPR/66.0.3515.95");
                urlConnection.setRequestProperty("Content-Length", Integer.toString(postDataLength));
                urlConnection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,image/apng,*/*;q=0.8,application/signed-exchange;v=b3;q=0.9");
                urlConnection.setRequestProperty("Cookie", authCookie);
                urlConnection.setDoOutput(true);
                urlConnection.setUseCaches(false);
                urlConnection.setInstanceFollowRedirects(false);

                try (DataOutputStream wr = new DataOutputStream(urlConnection.getOutputStream())) {
                    wr.write(postData);
                }

                responseBody = Functions.getResponseFromGetRequest(urlConnection);
                html = Jsoup.parse(responseBody);

                Elements stats = html.body().getElementsByClass("stat-block");

                statsMidMark = stats.get(0).getElementsByClass("stat-value").get(0).text();
                statsDebtsCount = stats.get(1).getElementsByClass("stat-value").get(0).text();
                statsPercentageOfVisits = stats.get(2).getElementsByClass("stat-value").get(0).text();
                System.out.println(statsMidMark + " " + statsDebtsCount + " " + statsPercentageOfVisits);

            } catch (SocketTimeoutException e) {
                System.out.println("Student stats request timeout!");
                getStudentStatsRequestStatus = RequestStatus.TIMEOUT;
                return new String[] {"", "", ""};
            } catch (Exception e) {
                System.out.println("Problems with statistics request");
                System.out.println(e.toString());
            } finally {
                if (urlConnection != null) {
                    urlConnection.disconnect();
                }
            }

            return new String[] { statsMidMark, statsDebtsCount, statsPercentageOfVisits };
        }

        protected void onPostExecute(String[] result) {
            super.onPostExecute(result);
            onGetStudentStatsRequestCompleted(result);

        }
    }






    /* --------------------------------- FrontEnd ------------------------------------------------------- */





    // studentLessons - JSONArray предметов с их name, semester, id
    // exercises - JSONObject где id предмета - массив с объектами (парами) с их id, topic, type, time, day, timeday (дата yyyy-mm-dd)
    // exercisesVisits - JSONObject где id предмета - объект, содержащий id пар, которые являются массивом с одним объектом внутри с id (какое-то свое, бесполезное), presence, point, delay, performance, visit_need, mark_need
    // teachers - JSONObject где id предмета - объект с их id, lastname, firstname, middlename
    // todayExercises - JSONArray (пары по дате) объектов с их id (пары), topic, name (предмета), shortname (для приложения), time, lid (id предмета)
    // studentLessons - JSONObject пар за выбранную дату, где каждое поле - массив, содержащий один объект с их id (какое-то свое, бесполезное), presence, point, delay, performance, visit_need, mark_need, review (массив), daypast


    // кнопки нужны глобально

    Button home;
    Button schedule;
    Button profile;
    Button lessons;
    Button exit;

    Button userHelp;
    Button scheduleChanges;
    Button scheduleNow;
    Button scheduleNext;

    // переменная для мониторинга активного контейнера

    enum ContainerName { PROFILE, HOME, SCHEDULE, LESSONS, LESSONS_INFORMATION, NOTIFICATION, LOADING, LOGIN }
    ContainerName activeContainer;


    public void setContainer(ContainerName newContainer) { // функция обновления активного контейнера
        switch (activeContainer) {
            case PROFILE: {
                main.removeView(profileScreen);
                break;
            }
            case HOME: {
                main.removeView(homeScreen);
                break;
            }
            case SCHEDULE: {
                main.removeView(scheduleScreen);
                break;
            }
            case LESSONS: {
                main.removeView(lessonsScreen);
                break;
            }
            case LESSONS_INFORMATION: {
                main.removeView(lessonsInformationScreen);
                break;
            }
            case NOTIFICATION: {
                main.removeView(notificationListScreen);
                break;
            }
            case LOGIN: {
                main.removeView(loginForm);
                break;
            }
            case LOADING: {
                main.removeView(loadingScreen);
            }
        }

        switch (newContainer) {
            case PROFILE: {
                main.addView(profileScreen);
                activeContainer = ContainerName.PROFILE;
                break;
            }
            case HOME: {
                main.addView(homeScreen);
                activeContainer = ContainerName.HOME;
                break;
            }
            case SCHEDULE: {
                main.addView(scheduleScreen);
                activeContainer = ContainerName.SCHEDULE;
                break;
            }
            case LESSONS: {
                main.addView(lessonsScreen);
                activeContainer = ContainerName.LESSONS;
                break;
            }
            case LESSONS_INFORMATION: {
                main.addView(lessonsInformationScreen);
                activeContainer = ContainerName.LESSONS_INFORMATION;
                break;
            }
            case NOTIFICATION: {
                main.addView(notificationListScreen);
                activeContainer = ContainerName.NOTIFICATION;
                break;
            }
            case LOGIN: {
                main.removeAllViews();
                main.addView(loginForm);
                activeContainer = ContainerName.LOGIN;
                break;
            }
            case LOADING: {
                main.addView(loadingScreen);
                activeContainer = ContainerName.LOADING;
            }
        }
    }

    public void setLoadingToList(ContainerName neededContainer) { // функция обновления активного контейнера
        switch (neededContainer) {
            case SCHEDULE: {
                LinearLayout box = findViewById(R.id.scheduleList);
                box.removeAllViews();
                box.addView(loadingScreen);
                break;
            }
            case LESSONS_INFORMATION: {
                LinearLayout box = findViewById(R.id.lessonsInformationList);
                box.removeAllViews();
                box.addView(loadingScreen);
                break;
            }
            case NOTIFICATION: {
                LinearLayout box = findViewById(R.id.notificationList);
                box.removeAllViews();
                box.addView(loadingScreen);
                break;
            }
        }
    }


//            JSONObject schedule = {
//              "now": [ // week
//                  [ // day
//                      {position: "I", start: "10", end: "11", name: "name", teacher: "teacher"}, // lesson
//                      {position: "II", start: "11", end: "12", name: "name", teacher: "teacher"}
//                  ],
//                  [ // day
//                      {position: "I", start: "10", end: "11", name: "name", teacher: "teacher"}, // lesson
//                      {position: "II", start: "11", end: "12", name: "name", teacher: "teacher"}
//                  ]
//
//              ]
//
//            };


    void buildFrontend() {

        //заранее высираем контент в lessonsScreen
        main.addView(lessonsScreen);

        LinearLayout lessonsList = findViewById(R.id.lessonsList);

//        System.out.println(statsMidMark + " " + statsDebtsCount + " " + statsPercentageOfVisits);

        for(int i = 0; i < studentLessons.length(); i++){
            JSONObject value;
            try {
                value = studentLessons.getJSONObject(i);
                TextView temp = new TextView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150);
                lp.setMargins(0,0,0, 50);
                temp.setLayoutParams(lp);
                temp.setText(value.getString("name") + " семестр " + value.getString("semester"));
                temp.setBackgroundColor(127);

                // самая важная вещь - id temp'а это id для JSONObject

                temp.setId(Integer.parseInt(value.getString("id")));


                // вешаем универсальный обработчик кликов для каждого предмета

                lessonInformationClickListener needMoreInfo = new lessonInformationClickListener();
                temp.setOnClickListener(needMoreInfo);
                lessonsList.addView(temp);

            } catch (JSONException e) {
                e.printStackTrace();
            }
        }


        //скрываем lessonsScreen
        main.removeView(lessonsScreen);

        // убираем регистрацию и подрубаем стартовый экран
        main.removeView(loadingScreen);
        main.removeView(loginForm);
        setContainer(ContainerName.PROFILE);
        main.addView(navigation);
        main.addView(userHelpScreen);

        // инициализируем картинку

        ImageView img = (ImageView) findViewById(R.id.profileImage);
        img.setImageBitmap(bitmap);
        img.setScaleType(ImageView.ScaleType.FIT_XY);


        // создаем слушатели для кнопок

        home = findViewById(R.id.home);
        schedule = findViewById(R.id.schedule);
        profile = findViewById(R.id.profile);
        lessons = findViewById(R.id.lessons);
        exit = findViewById(R.id.exit);
        userHelp = findViewById(R.id.notification);



        // наш обработчик кликов

        navigationButtonClickListener wasClicked = new navigationButtonClickListener();

        userHelp.setOnClickListener(wasClicked);

        home.setOnClickListener(wasClicked);
        schedule.setOnClickListener(wasClicked);
        profile.setOnClickListener(wasClicked);
        lessons.setOnClickListener(wasClicked);
        exit.setOnClickListener(wasClicked);

        scheduleChanges.setOnClickListener(wasClicked);

        // под удаление - вывод инфы о юзере

        TextView text = new TextView(this);
        LinearLayout.LayoutParams lpText = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lpText.setMargins(50,50,50,50);
        text.setText(studentFIO + " - " + studentGroup + " " + statsDebtsCount + " долгов " + statsMidMark + " средний балл " + statsPercentageOfVisits +" посещений");
        profileScreen.addView(text);

        final LinearLayout todayLessonsView = (LinearLayout) findViewById(R.id.todayLessonsView);


        // высираем сегодняшние пары перебором

        for(int i = 0; i < exercisesByDay.length(); i++){
            JSONObject value;
            try {
                value = exercisesByDay.getJSONObject(i);

                TextView temp = new TextView(this);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.setMargins(0, 0, 0, 50);
                temp.setLayoutParams(lp);
                temp.setText(value.getString("name") + " " + value.getString("time") + " пара ");

                // узнаем подробную информацию о паре

                JSONObject valueInfo;
                try {
                    valueInfo = exercisesVisitsByDay.getJSONArray(value.getString("id")).getJSONObject(0);

                    String presence = valueInfo.getString("presence").equals("0") ? " присутствие: нет " : " присутствие: да ";
                    String point = valueInfo.getString("point").toString().equals("null")  ? " оценка: нет " : " оценка: да ";
                    switch (valueInfo.getString("point")) {
                        case "2": {
                            point = " оценка: 2";
                            break;
                        }
                        case "3": {
                            point = " оценка: 3";
                            break;
                        }
                        case "4": {
                            point = " оценка: 4";
                            break;
                        }
                        case "5": {
                            point = " оценка: 5";
                            break;
                        }
                    }
                    String delay = valueInfo.getString("delay").toString().equals("null")  ? " опоздание: нет " : " опоздание: да ";
                    String performance = valueInfo.getString("performance").equals("null") ? " активность: нет " : " активность: да ";

                    temp.setText(temp.getText() + presence + point + delay + performance);

                } catch (JSONException e) {
                    e.printStackTrace();
                }

                todayLessonsView.addView(temp);

            } catch (JSONException e) {
                e.printStackTrace();
            }
        }
    }


    // обработчик нажатий на кнопки навигации

    class navigationButtonClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v)
        {

            // убираем старый контейнер

            switch (activeContainer) {
                case PROFILE: {
                    if (v.getId() == profile.getId()) return;
                    main.removeView(profileScreen);
                    break;
                }
                case HOME: {
                    if (v.getId() == home.getId()) return;
                    main.removeView(homeScreen);
                    break;
                }
                case SCHEDULE: {
                    if (v.getId() == schedule.getId()) return;
                    main.removeView(scheduleScreen);
                    break;
                }
                case LESSONS: {
                    if (v.getId() == lessons.getId()) return;
                    main.removeView(lessonsScreen);
                    break;
                }
                case LESSONS_INFORMATION: {
                    main.removeView(lessonsInformationScreen);
                    break;
                }
                case NOTIFICATION: {
                    if (v.getId() == userHelp.getId()) return;
                    main.removeView(notificationListScreen);
                    break;
                }
            }

            // и добавляем новый

            if (v.getId() == home.getId()) {
                System.out.println("You clicked home");
                setContainer(ContainerName.HOME);
            }

            if (v.getId() == schedule.getId()) {
                System.out.println("You clicked schedule");
                setContainer(ContainerName.SCHEDULE);
            }

            if (v.getId() == profile.getId()) {
                System.out.println("You clicked profile");
                setContainer(ContainerName.PROFILE);
            }

            if (v.getId() == lessons.getId()) {
                System.out.println("You clicked lessons");
                setContainer(ContainerName.LESSONS);
            }

            if (v.getId() == userHelp.getId() || v.getId() == scheduleChanges.getId()) {
                System.out.println("You clicked notifications");
                setContainer(ContainerName.NOTIFICATION);
            }

            if (v.getId() == exit.getId()) {
                preferences = MainActivity.this.getPreferences(Context.MODE_PRIVATE);
                preferencesEditor = preferences.edit();
                preferencesEditor.clear();
                preferencesEditor.apply();

                nowWeekScheduleCalled = false;
                nextWeekScheduleCalled = false;
                readyExercisesByLesson = new JSONObject();

                resetRequestsStatuses();

                setContainer(ContainerName.LOGIN);
                Button submit = findViewById(R.id.loginFormSubmit);
                final TextInputEditText login = findViewById(R.id.loginFormLogin);
                final TextInputEditText password = findViewById(R.id.loginFormPassword);
                submit.setOnClickListener(new View.OnClickListener() {

                    // отправляем запрос
                    @Override
                    public void onClick(View v) {
                        sendLoginRequest(new String[] {
                                login.getText().toString(),
                                password.getText().toString()
                        });
                    }
                });
            }

            // но если кликнута кнопка изменений в расписании, нужно еще выкинуть контент от вк



            if (activeContainer == ContainerName.NOTIFICATION) {

                sendGetVKWallPostsRequest(new String[] {"10"});
                setLoadingToList(ContainerName.NOTIFICATION);


            }



        }

    }

    // обработчик нажатий на предметы в lessons

    class lessonInformationClickListener implements View.OnClickListener {
        @Override
        public void onClick(View v)
        {

            // обновляем активный экран

            main.removeView(lessonsScreen);
            activeContainer = ContainerName.LESSONS_INFORMATION;
            main.addView(lessonsInformationScreen);


            LinearLayout lessonsInformationList = findViewById(R.id.lessonsInformationList);

            // очищаем scrollview


            JSONArray buffer = null;
            try {
                buffer = readyExercisesByLesson.getJSONArray(v.getId()+"");
            } catch (JSONException e) {
                e.printStackTrace();
            }
            System.out.println(buffer);
            if (buffer == null) {
                setLoadingToList(ContainerName.LESSONS_INFORMATION);
                sendGetExercisesByLessonRequest(new String[] {v.getId()+""});
            } else {


                for (int k = 0; k < buffer.length(); k++) {
                    JSONObject value;
                    try {

                        value = buffer.getJSONObject(k);
                        TextView temp = new TextView(getApplicationContext());
                        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 150);
                        lp.setMargins(0,0,0, 50);
                        temp.setLayoutParams(lp);
                        temp.setText(value.getString("topic") + " и эта пара была " + value.getString("day"));
                        temp.setBackgroundColor(167);


                        // получаем подробную информацию о паре

                        JSONObject valueInfo;
                        try {
                            valueInfo = readyExercisesByLessonVisits.getJSONArray(value.getString("id")).getJSONObject(0);

                            String presence = valueInfo.getString("presence").equals("0") ? " присутствие: нет " : " присутствие: да ";
                            String point = valueInfo.getString("point").toString().equals("null")  ? " оценка: нет " : " оценка: да ";
                            switch (valueInfo.getString("point")) {
                                case "2": {
                                    point = " оценка: 2";
                                    break;
                                }
                                case "3": {
                                    point = " оценка: 3";
                                    break;
                                }
                                case "4": {
                                    point = " оценка: 4";
                                    break;
                                }
                                case "5": {
                                    point = " оценка: 5";
                                    break;
                                }
                            }
                            String delay = valueInfo.getString("delay").toString().equals("null")  ? " опоздание: нет " : " опоздание: да ";
                            String performance = valueInfo.getString("performance").toString().equals("null") ? " активность: нет " : " активность: да ";

                            temp.setText(temp.getText() + presence + point + delay + performance);
                        } catch (JSONException e) {
                            e.printStackTrace();
                        }



                        // опять же id - ключ для следующего массива

                        temp.setId(Integer.parseInt(value.getString("id")));
                        lessonsInformationList.addView(temp);

                    } catch (JSONException e) {
                        e.printStackTrace();
                    }
                }

            }

        }

    }

}