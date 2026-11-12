package Controller;

import org.json.JSONObject;
import jakarta.servlet.RequestDispatcher;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.PrintWriter;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

public class CommonFunctions {

    public static void readData(HttpServletRequest httpServletRequest, StringBuilder stringBuilder) {
        try {
            BufferedReader bufferedReader = httpServletRequest.getReader();
            String line;
            while ((line = bufferedReader.readLine()) != null) {
                stringBuilder.append(line);
            }
        } catch (IOException ioException) {
            System.out.println("Failed to read data: " + ioException.getMessage() + " (Controller/CommonFunctions.ja" +
                    "va)");
        }
    }

    public static void setCharacterEncoding(HttpServletResponse httpServletResponse) {
        httpServletResponse.setContentType("application/json");
        httpServletResponse.setCharacterEncoding("UTF-8");
    }
    public static void printWriterProcess(HttpServletResponse httpServletResponse, JSONObject jsonObject) {
        try {
            PrintWriter printWriter = httpServletResponse.getWriter();
            printWriter.print(jsonObject);
            printWriter.flush();
        } catch (IOException ioException) {
            System.out.println(
                    "Failed to print writer: " + ioException.getMessage() + " (Controller/CommonFunctions.java)");
        }
    }

    public static void pageForward(
            HttpServletRequest httpServletRequest, HttpServletResponse httpServletResponse, java.lang.String page) {
        try {
            RequestDispatcher dispatcher = httpServletRequest.getRequestDispatcher(page);
            dispatcher.forward(httpServletRequest, httpServletResponse);
        } catch (ServletException | IOException exception) {
            System.out.println("Error forwarding to page: " + page);
            System.out.println(exception.getMessage() + " (Controller/CommonFunctions.java)");
            try {
                httpServletResponse.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                        "An internal error occurred. Please try again later.");
            } catch (IOException ioException) {
                System.out.println("Failed to send error response: " + ioException.getMessage() +
                        " (Controller/CommonFunctions.java)");
            }
        }
    }

    public static void logOff(HttpServletResponse httpServletResponse, java.lang.String message) {
        try {
            java.lang.String encodedMessage = URLEncoder.encode(message, StandardCharsets.UTF_8);
            httpServletResponse.sendRedirect("/Investment_and_Financial_Management" + "?message=" + encodedMessage);
        } catch (IOException ioException) {
            System.out.println("Failed to send error response: " + ioException.getMessage() +
                    " (Controller/CommonFunctions.java)");
        }
    }

}
