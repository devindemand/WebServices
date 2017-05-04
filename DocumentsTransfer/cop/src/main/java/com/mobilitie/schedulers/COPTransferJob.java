package com.mobilitie.schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

import org.apache.log4j.DailyRollingFileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

public class COPTransferJob {

	public void sampleJobMethod() {
		String command = "python C:/pythonscripts/sms_mtrac_cop.py -p C:/pythonscripts/Passwords.json";
		String s = null;

		try {
			Logger logger = createLogger();
			logger.debug("************** COP Data transfer from MTRAC to SMS && SMS to MTRAC STARTED**************");
			Process p = Runtime.getRuntime().exec(command);
			BufferedReader stdInput = new BufferedReader(new InputStreamReader(
					p.getInputStream()));
			BufferedReader stdError = new BufferedReader(new InputStreamReader(
					p.getErrorStream()));
			// read the output from the command
			logger.debug("Here is the standard output of the command:\n");
			while ((s = stdInput.readLine()) != null) {
				logger.debug(s);
			}
			// read any errors from the attempted command
			logger.debug("Here is the standard error of the command (if any):\n");
			while ((s = stdError.readLine()) != null) {
				logger.debug(s);
			}
			logger.debug("************** COP Data transfer from MTRAC to SMS && SMS to MTRAC ENDED**************");
		} catch (IOException ioExcep) {
			ioExcep.printStackTrace();
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	private Logger createLogger() {

		PatternLayout layout = new PatternLayout();
		String conversionPattern = "[%p] %d %c %M - %m%n";
		layout.setConversionPattern(conversionPattern);
		// creates daily rolling file appender
		DailyRollingFileAppender rollingAppender = new DailyRollingFileAppender();
		rollingAppender.setFile("C:/Integrations/Logs/COPTransferJob.log");
		rollingAppender.setDatePattern("'.'yyyy-MM-dd");
		rollingAppender.setLayout(layout);
		rollingAppender.activateOptions();

		// configures the root logger
		Logger rootLogger = Logger.getRootLogger();
		rootLogger.setLevel(Level.DEBUG);
		rootLogger.addAppender(rollingAppender);

		// creates a custom logger and log messages
		Logger logger = Logger.getLogger(COPTransferJob.class);
		return logger;

	}
}
