package com.gemini.cli;

import com.google.genai.Client;
import com.google.genai.types.GenerateContentResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Scanner;

public class GenerateTextFromTextInput {
    @SuppressWarnings("resource")
    private static String promptFromStdin(String message) {
        System.out.println(message);
        Scanner input = new Scanner(System.in);
        return input.nextLine();
    }

    public static void main(String[] args) {
        // The client gets the API key from the environment variable `GEMINI_API_KEY`.
        Client client = Client.builder().apiKey("").build(); // <---- Enter API KEY in the apiKey() function

    String prompt = null; // will be set either from ICS summarization construction or direct user text
        // Ability to point to .ics file via:
        // 1. First arg is a path
        // 2. Arg format --ics=path
        // 3. Arg pair -f path
        // 4. Interactive: user pastes/enters a .ics path when prompted
        String icsPath = null;
        if (args != null && args.length > 0) {
            for (int i = 0; i < args.length; i++) {
                String a = args[i];
                if (a.endsWith(".ics") && Files.exists(Paths.get(a))) {
                    icsPath = a;
                    break;
                } else if (a.startsWith("--ics=")) {
                    String possible = a.substring("--ics=".length());
                    if (Files.exists(Paths.get(possible))) {
                        icsPath = possible;
                        break;
                    }
                } else if (a.equals("-f") && i + 1 < args.length) {
                    String possible = args[i + 1];
                    if (possible.endsWith(".ics") && Files.exists(Paths.get(possible))) {
                        icsPath = possible;
                        break;
                    }
                }
            }
        }

        if (icsPath == null) {
            String entered = promptFromStdin("Enter a prompt OR path to a .ics file:");
            Path maybe = Paths.get(entered.trim());
            if (entered.trim().endsWith(".ics") && Files.exists(maybe) && Files.isRegularFile(maybe)) {
                icsPath = entered.trim();
            } else {
                prompt = entered; // treat as normal text prompt
            }
        }

        if (icsPath != null) {
            try {
                Path p = Paths.get(icsPath);
                String content = Files.readString(p, StandardCharsets.UTF_8);
                prompt = "Summarize the calendar events from the following ICS file.\n" +
                        "- Group by date (local to the event if available).\n" +
                        "- For each event, show start–end time, title, and location if present.\n" +
                        "- Note all-day events clearly.\n" +
                        "- End with a brief high-level summary (counts, notable days).\n\n" +
                        "ICS content:\n\n" + content;
                System.out.println("Loaded ICS file: " + icsPath);
            } catch (Exception e) {
                System.out.println("Failed to read ICS file (" + icsPath + "): " + e.getMessage());
                prompt = promptFromStdin("Enter a fallback prompt:");
            }
        }

        if (prompt == null) {
            // Safety net: if for some reason prompt wasn't set, ask user.
            prompt = promptFromStdin("Enter a prompt:");
        }

        GenerateContentResponse response = client.models.generateContent(
                "gemini-2.5-flash",
                prompt,
                null);

        System.out.println(response.text());
    }
}