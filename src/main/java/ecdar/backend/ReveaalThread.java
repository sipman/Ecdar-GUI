package ecdar.backend;

import ecdar.Ecdar;
import ecdar.abstractions.QueryState;

import java.io.*;
import java.util.function.Consumer;

public class ReveaalThread extends BackendThread {
    public ReveaalThread(String query, Consumer<Boolean> success, Consumer<BackendException> failure, QueryListener queryListener) {
        super(query, success, failure, queryListener);
    }

    public void run() {
        ProcessBuilder pb = new ProcessBuilder("src/Reveaal", Ecdar.projectDirectory.get(), query); // Space added to signal EOI
        pb.redirectErrorStream(true);
        try {
            //Start the j-Ecdar process
            Process ReveaalEngineInstance = pb.start();

            //Communicate with the j-Ecdar process
            try (
                    var ReveaalReader = new BufferedReader(new InputStreamReader(ReveaalEngineInstance.getInputStream()));
            ) {
                //Read the result of the query from the j-Ecdar process
                String line;
                QueryState result = QueryState.RUNNING;
                while ((line = ReveaalReader.readLine()) != null) {
                    System.out.println(line);
                    if (hasBeenCanceled.get()) {
                        cancel(ReveaalEngineInstance);
                        return;
                    }

                    // Skip the returned query and possible notes, as these should not be shown in the GUI
                    if (line.startsWith("note:")){
                        continue;
                    }

                    // Process the query result
                    if ((line.endsWith("true") || line.startsWith("Query: Query")) && (result.getStatusCode() <= QueryState.SUCCESSFUL.getStatusCode())) {
                        result = QueryState.SUCCESSFUL;
                    } else if (line.endsWith("result: false")){
                        result = QueryState.ERROR;
                    } else {
                        result = QueryState.SYNTAX_ERROR;
                    }

                    if (result.getStatusCode() == QueryState.SUCCESSFUL.getStatusCode()) {
                        success.accept(true);
                    } else if (result.getStatusCode() == QueryState.ERROR.getStatusCode()){
                        success.accept(false);
                    } else if (result.getStatusCode() == QueryState.SYNTAX_ERROR.getStatusCode()) {
                        failure.accept(new BackendException.QueryErrorException(line));
                    } else {
                        failure.accept(new BackendException.BadBackendQueryException(line));
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void cancel(Process ReveaalEngineInstance) {
        ReveaalEngineInstance.destroy();
        failure.accept(new BackendException.QueryErrorException("Canceled"));
    }
}
