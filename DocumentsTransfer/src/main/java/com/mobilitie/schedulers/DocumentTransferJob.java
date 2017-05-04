package com.mobilitie.schedulers;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.ws.rs.core.MultivaluedMap;

import org.apache.commons.io.IOUtils;
import org.apache.log4j.DailyRollingFileAppender;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;
import org.json.JSONException;
import org.json.JSONObject;

import com.sun.jersey.api.client.Client;
import com.sun.jersey.api.client.ClientResponse;
import com.sun.jersey.api.client.WebResource;
import com.sun.jersey.api.client.filter.HTTPBasicAuthFilter;
import com.sun.jersey.core.util.MultivaluedMapImpl;

public class DocumentTransferJob {
	public void sampleJobMethod() {
		System.out.println("Started Processing Documents Transfer");
		Logger logger = createLogger();
		long startTime = System.currentTimeMillis();
		List<String> processedCandidateCodes = new ArrayList<String>();
		List<String> failedCandidateCodes = new ArrayList<String>();
		List<String> unmappedCandDocType = new ArrayList<String>();
		boolean isFailed = false;
		int responseCode = 0;
		String pendCandidateCode = "";
		String pendingCandidateDocType = "";
		Connection con = null;
		Connection conTemp = null;
		Statement pendingCandStmt = null;
		ResultSet rs = null;
		ResultSet rsPendCand = null;
		String documentPath = "";
		String docName = "";
		try {
			Class.forName("com.microsoft.sqlserver.jdbc.SQLServerDriver");
			String pendingCandDocSQL = "SELECT [CandidateID],[DocumentType],[DocumentPath],[uDocumentFileExtenstion], [uFileName] FROM [dbo].[ApprovedDocsforOV] where [IsRead] = 'N' or [MtracUpdated]='N'";
			String connectionUrlToTemp = "jdbc:sqlserver://172.16.11.7:1433;databaseName=AuditDocs;user=iamsmsadmin;password=wh0th3H3LrU012;";
			conTemp = DriverManager.getConnection(connectionUrlToTemp);
			pendingCandStmt = conTemp.createStatement();
			rsPendCand = pendingCandStmt.executeQuery(pendingCandDocSQL);
			String mTracMappedField = "";
			String base64Encoded = "";
			JSONObject objectForMTRAC = null;
			while (rsPendCand.next()) {
				pendCandidateCode = rsPendCand.getString(1);
				pendingCandidateDocType = rsPendCand.getString(2);
				documentPath = rsPendCand.getString(3);
				docName = rsPendCand.getString(5);
				base64Encoded = download(documentPath, logger);
				mTracMappedField = getMapping(pendingCandidateDocType);
				if (!mTracMappedField.equals("")) {

					objectForMTRAC = postData(base64Encoded, mTracMappedField,
							docName, logger);
					responseCode = postDataToMTRAC(objectForMTRAC,
							pendCandidateCode, logger);
					if (responseCode != 200) {
						failedCandidateCodes.add(pendCandidateCode);
						isFailed = true;
						updateSMS(pendCandidateCode, isFailed, logger);
					} else {
						processedCandidateCodes.add(pendCandidateCode);
						isFailed = false;
						updateSMS(pendCandidateCode, isFailed, logger);
					}
				} else {
					logger.debug("The candidate:" + "\t" + pendCandidateCode
							+ "\t" + "with the doctype " + "\t"
							+ pendingCandidateDocType + "\t"
							+ "is not mapped to any field in MTRAC3.0 ");
					if (!unmappedCandDocType.contains(pendingCandidateDocType)) {
						unmappedCandDocType.add(pendingCandidateDocType);
					}

				}

			}
			sendNotification(failedCandidateCodes, processedCandidateCodes,
					unmappedCandDocType, startTime, logger);
			System.out.println("Ended Processing Documents Transfer");

		}

		catch (Exception e) {
			sendErrorEmail(e.getMessage(), logger);
		} finally {
			if (rs != null)
				try {
					rs.close();
				} catch (Exception e) {
				}
			if (con != null)
				try {
					con.close();
					conTemp.close();
				} catch (Exception e) {
				}
		}

	}

	private void sendNotification(List<String> failedCandidateCodes,
			List<String> processedCandidateCodes,
			List<String> unmappedDocTypes, long startTime, Logger logger) {
		long endTime = System.currentTimeMillis();
		long totalTimeForExecution = 0l;

		// TODO move these credentials out , also encrypt the password
		final String username = "SMSOVAlerts@mobilitie.com";
		final String password = "Password1!";

		Properties props = new Properties();
		props.put("mail.smtp.auth", "true");
		props.put("mail.smtp.starttls.enable", "true");
		props.put("mail.smtp.host", "smtp.office365.com");
		props.put("mail.smtp.port", "587");

		Session session = Session.getInstance(props,
				new javax.mail.Authenticator() {
					protected PasswordAuthentication getPasswordAuthentication() {
						return new PasswordAuthentication(username, password);
					}
				});

		try {
			Calendar calendar = Calendar.getInstance();
			Date currentDate = calendar.getTime();
			totalTimeForExecution = endTime - startTime;
			totalTimeForExecution = (totalTimeForExecution) / (1000);
			StringBuilder textForEmail = new StringBuilder(1000);
			Message message = new MimeMessage(session);
			message.setFrom(new InternetAddress("SMSOVAlerts@mobilitie.com"));
			message.setRecipients(
					Message.RecipientType.TO,
					InternetAddress
							.parse("kreddy@mobilitie.com,sareh.salamipour@mobilitie.com,bryce@mobilitie.com"));
			message.setSubject("Document Transfer Status from SMS to MTRAC3.0");
			textForEmail.append("The script was executed at:" + currentDate
					+ "\n");
			textForEmail.append("\n");
			if (failedCandidateCodes.size() != 0
					|| processedCandidateCodes.size() != 0
					|| unmappedDocTypes.size() != 0) {
				textForEmail
						.append("Total time taken in seconds for the script execution is : "
								+ totalTimeForExecution + "\n");
				textForEmail.append("\n");

				if (processedCandidateCodes.size() != 0) {
					textForEmail
							.append("===========================================================================================");
					textForEmail.append("\n");
					textForEmail
							.append("Documents for the following candidate codes have been transferred to MTRAC3.0 ");
					textForEmail.append("\n");
					textForEmail
							.append(convertListTOString(processedCandidateCodes));
					textForEmail.append("\n");
					textForEmail
							.append("===========================================================================================");
					textForEmail.append("\n");
				}

				if (failedCandidateCodes.size() != 0) {
					textForEmail
							.append("===========================================================================================");
					textForEmail.append("\n");
					textForEmail
							.append("Documents for the following candidate codes have NOT  been transferred to MTRAC3.0 - Please validate the below candidates in MTRAC3.0");
					textForEmail.append("\n");
					textForEmail
							.append(convertListTOString(failedCandidateCodes));
					textForEmail.append("\n");
					textForEmail
							.append("===========================================================================================");
					textForEmail.append("\n");
				}
				if (unmappedDocTypes.size() != 0) {
					textForEmail
							.append("===========================================================================================");
					textForEmail.append("\n");
					textForEmail
							.append("There are no mappings in MTRAC for the below document types in  SMS:"
									+ "\n");
					textForEmail.append(convertListTOString(unmappedDocTypes));
					textForEmail.append("\n");
					textForEmail
							.append("===========================================================================================");
					textForEmail.append("\n");
				}

				textForEmail.append("\n");
				message.setText(textForEmail.toString());
			} else {
				textForEmail.append("\n");
				textForEmail
						.append("Total time taken in seconds for the script execution is : "
								+ totalTimeForExecution + "\n");
				textForEmail.append("\n");
				textForEmail
						.append("No approved documents eligible for transfer found in SMS");
				message.setText(textForEmail.toString());
			}
			Transport.send(message);
			logger.debug("Email sent ");
		} catch (MessagingException e) {
			throw new RuntimeException(e);
		}
	}

	private void updateSMS(String pendCandidateCode, boolean isFailed,
			Logger logger) {

		Connection conTemp = null;
		try {
			String updateSQL = "";
			String connectionUrlToTemp = "jdbc:sqlserver://172.16.11.7:1433;databaseName=AuditDocs;user=iamsmsadmin;password=wh0th3H3LrU012;";
			if (isFailed) {
				updateSQL = "update  [dbo].[ApprovedDocsforOV] set [IsRead] = 'Y' , [MtracUpdated] ='N' where [CandidateID] =?";
			} else {
				updateSQL = "update  [dbo].[ApprovedDocsforOV] set [IsRead] = 'Y' , [MtracUpdated] ='Y' where [CandidateID] =? ";
			}

			conTemp = DriverManager.getConnection(connectionUrlToTemp);
			PreparedStatement ps = conTemp.prepareStatement(updateSQL);
			ps.setString(1, pendCandidateCode);
			ps.executeUpdate();

		} catch (SQLException e) {
			if (conTemp != null) {
				try {
					conTemp.close();
				} catch (SQLException e1) {
					sendErrorEmail(e1.getMessage(), logger);
				}
			}

		} catch (Exception e) {
			sendErrorEmail(e.getMessage(), logger);
		}

	}

	private JSONObject postData(String base64Encoded, String mTracMappedField,
			String fileName, Logger logger) throws JSONException {
		JSONObject fieldObj = new JSONObject();
		JSONObject mappedField = new JSONObject();
		JSONObject fieldNameNData = new JSONObject();
		fieldObj.put("fields", mappedField);
		mappedField.put(mTracMappedField, fieldNameNData);
		fieldNameNData.put("file_name", fileName);
		fieldNameNData.put("data", base64Encoded);
		return fieldObj;

	}

	private String getMapping(String pendingCandidateDocType) {
		String mappedField = "";
		switch (pendingCandidateDocType) {
		case "2CSURVEY":
			mappedField = "P_D_AE_2C_SURVEY";
			break;
		case "AMCERT3KM":
			mappedField = "P_D_REG_AM_CERTIF_3KM";
			break;
		case "AMCERT5KM":
			mappedField = "P_D_REG_AM_CERTIF_5KM";
			break;
		case "AMLETTER3KM":
			mappedField = "P_D_REG_AM_LETTER_3KM";
			break;
		case "AMLETTER5KM":
			mappedField = "P_D_REG_AM_LETTER_5KM";
			break;
		case "AMSCREEN3KM":
			mappedField = "P_D_REG_AM_SCREEN_3KM";
			break;
		case "AMSCREEN5KM":
			mappedField = "P_D_REG_AM_SCREEN_5KM";
			break;
		case "AMPOST":
			mappedField = "P_D_REG_AM_STUDY_POST_CON";
			break;
		case "AMPRE":
			mappedField = "P_D_REG_AM_STUDY_PRE_CON";
			break;
		case "ASRPUBNOTICE":
			mappedField = "P_D_REG_ASR_PUBLIC_NOTICE";
			break;
		case "ASR854PART1":
			mappedField = "P_D_REG_ASR_FORM_854_PART_1";
			break;
		case "ASR854PART2":
			mappedField = "P_D_REG_ASR_FORM_854_PART_2";
			break;
		case "AZPCHECKLIST":
			mappedField = "P_D_AZP_CHECKLIST";
			break;
		case "BPDWGFINAL":
			mappedField = "P_D_DWG_APPR_PERMIT_DWGS";
			break;
		case "ASBUILT":
			mappedField = "P_D_AE_AS_BUILT_DWGS";
			break;
		case "WDRWBPAPP":
			mappedField = "P_D_PERM_BP_APP_WTHDRWL_BY_MOBILITIE";
			break;
		case "BPDELCONF":
			mappedField = "P_D_PERM_BP_APPLIC_CONFIRM";
			break;
		case "BPCOVLET":
			mappedField = "P_D_PERM_BP_APPLIC_CVR_LETTER";
			break;
		case "ESCALATEBP":
			mappedField = "P_D_PERM_BP_ESCAL";
			break;
		case "REJECTBPAPP":
			mappedField = "P_D_PERM_BP_JX_REJECT_OF_APPLIC";
			break;
		case "REJECTBP":
			mappedField = "P_D_PERM_BP_JX_REJECT_OF_PERMIT";
			break;
		case "BONDREQ":
			mappedField = "P_D_FIN_BOND_REQUEST_FORM";
			break;
		case "BOND":
			mappedField = "P_D_FIN_BOND_SUBMIT_TO_JX";
			break;
		case "BP":
			mappedField = "P_D_PERM_BUILDING_PERMIT";
			break;
		case "BPAPP":
			mappedField = "P_D_PERM_BUILDING_PERMIT_APP";
			break;
		case "BUSINESSREG":
			mappedField = "P_D_FIN_BUS_REGIST_CERT";
			break;
		case "BUSINESSREGAPP":
			mappedField = "P_D_FIN_BUS_REGIST_REQUEST_APP";
			break;
		case "BUSREGCHECKREQ":
			mappedField = "P_D_FIN_BUS_REGISTR_CHECK_REQ";
			break;
		case "LUPCOVLET":
			mappedField = "P_D_PERM_CDS_100%_REDLINE";
			break;
		case "CONSTDWGS100":
			mappedField = "P_D_DWG_CDS_100_FOR_CX";
			break;
		case "RL1CONSTDWGS90 ":
			mappedField = "P_D_DWG_CDS_90_REDLINE";
			break;
		case "CONSTDWGS90":
			mappedField = "P_D_DWG_CDS_90_FOR_BP";
			break;
		case "RLBPDWG":
			mappedField = "P_D_DWG_CDS_REDLINE_BP";
			break;
		case "REDLINE":
			mappedField = "P_D_CONST_CD_REDLINES_CHANGES";
			break;
		case "BPDWG":
			mappedField = "P_D_DWG_CDS_SUBMIT_BP";
			break;
		case "COIREQ":
			mappedField = "P_D_FIN_COI_REQUEST_FORM";
			break;
		case "CPUC159A":
			mappedField = "P_D_REG_CPUC_159-A";
			break;
		case "CPUCAPP":
			mappedField = "P_D_REG_CPUC_APPLIC";
			break;
		case "CPUCCERT":
			mappedField = "P_D_REG_CPUC_CERTIFICATE";
			break;
		case "CPUC":
			mappedField = "P_D_REG_CPUC_FINAL_APPROVED";
			break;
		case "COI":
			mappedField = "P_D_FIN_COI";
			break;
		case "CHECKREQUEST":
			mappedField = "P_D_FIN_CHECK_REQUEST_BACKUP";
			break;
		case "CHECK":
			mappedField = "P_D_FIN_CHECK_SUBMIT_TO_JX";
			break;
		case "VOIDCHECK":
			mappedField = "P_D_FIN_CHECK_VOIDED";
			break;
		case "VOIDBUSREGCHECK":
			mappedField = "P_D_FIN_CHECK_VOIDED_FOR_BUS_REGISTR";
			break;
		case "VOIDGCREGCHECK":
			mappedField = "P_D_FIN_CHECK_VOIDED_FOR_GC_REGISTR";
			break;
		case "BUSREGCHECK":
			mappedField = "P_D_FIN_CHECK_FOR_BUS_REGIST";
			break;
		case "GCREGCHECK":
			mappedField = "P_D_FIN_CHECK_FOR_GC_REGISTR";
			break;
		case "COMBOAGMNT":
			mappedField = "P_D_REOPS_COMB_ROW_AND_POLE_AGMT";
			break;
		case "CC":
			mappedField = "P_D_CONST_CHKLIST_COMP";
			break;
		case "DEPOSITREQ":
			mappedField = "P_D_FIN_DEPOSIT_REQUEST_FORM";
			break;
		case "DEPOSIT":
			mappedField = "P_D_FIN_DEPOSIT_SUBMIT_TO_JX";
			break;
		case "EMESTUDY":
			mappedField = "P_D_REG_EME_STUDY";
			break;
		case "EPDELCONF":
			mappedField = "P_D_PERM_EP_APP_CONFIRM";
			break;
		case "EPCOVLET":
			mappedField = "P_D_PERM_EP_APP_CVR_LETTER";
			break;
		case "WDRWEPAPP":
			mappedField = "P_D_PERM_EP_APP_WTHDRWL_BY_MOBILITIE";
			break;
		case "EPAPP":
			mappedField = "P_D_PERM_EP_APPLIC";
			break;
		case "EPDWG":
			mappedField = "P_D_DWG_EP_DWGS";
			break;
		case "ESCALATEEP":
			mappedField = "P_D_PERM_EP_ESCAL";
			break;
		case "EP":
			mappedField = "P_D_PERM_EP_FINAL_APPROVED";
			break;
		case "EPDWGFINAL":
			mappedField = "P_D_DWG_FINAL_DWGS";
			break;
		case "REJECTEPAPP":
			mappedField = "P_D_PERM_EP_JX_REJECT_OF_APPLIC";
			break;
		case "REJECTEP":
			mappedField = "P_D_PERM_EP_JX_REJECT_OF_PERMIT";
			break;
		case "ESCROWREQ":
			mappedField = "P_D_FIN_ESCROW_REQUEST_FORM";
			break;
		case "ESCROW":
			mappedField = "P_D_FIN_ESCROW_SUBMIT_TO_JX";
			break;
		case "INDEMNITY":
			mappedField = "P_D_FIN_ESCROWS_REQUIRED_BY_JX";
			break;
		case "FAACERT":
			mappedField = "P_D_REG_FAA_CERTIF";
			break;
		case "FAACERTAPP":
			mappedField = "P_D_REG_FAA_CERTIF_APP";
			break;
		case "FAAPOSTCONST":
			mappedField = "P_D_REG_FAA_FORM_7460-2_POSTCON";
			break;
		case "FAAPRECONST":
			mappedField = "P_D_REG_FAA_FORM_7460-2_PRECON";
			break;
		case "FAASCREEN":
			mappedField = "P_D_REG_FAA_SCREEN";
			break;
		case "FCCREG":
			mappedField = "P_D_REG_FCC_REGIST";
			break;
		case "FOUNDATION":
			mappedField = "P_D_AE_FOUNDATION_DESIGN";
			break;
		case "GCREG":
			mappedField = "P_D_FIN_GC_REGISTR_CERT";
			break;
		case "GCREGCHECKREQ":
			mappedField = "P_D_FIN_GC_REGISTR_CHECK_REQ";
			break;
		case "GCREGAPP":
			mappedField = "P_D_FIN_GC_REGISTR_REQUEST_APP";
			break;
		case "GEOTECH":
			mappedField = "P_D_AE_GEOTECH";
			break;
		case "INDEMNITYREQ":
			mappedField = "P_D_FIN_INDEMNITY_REQUEST_FORM";
			break;
		case "LUPDELCONF":
			mappedField = "P_D_PERM_LUP_APP_CONFIRM";
			break;
		case "WDRWZPAPP":
			mappedField = "P_D_PERM_LUP_APP_WTHDRWL_BY_MOBILITIE";
			break;
		case "ESCALATELUP":
			mappedField = "P_D_PERM_LUP_ESCAL";
			break;
		case "EXTENDLUPCONF":
			mappedField = "P_D_REOPS_LUP_EXTEN_CONFIRM";
			break;
		case "EXTENDLUPREQUEST":
			mappedField = "P_D_REOPS_LUP_EXTEN_REQEUST";
			break;
		case "EXTENDLUPRESCHEDULE":
			mappedField = "P_D_REOPS_LUP_EXTEN_RESCHEDULE";
			break;
		case "EXTENDLUP":
			mappedField = "P_D_REOPS_LUP_EXTEN_FROM_JX";
			break;
		case "LUPDWGFINAL":
			mappedField = "P_D_DWG_LUP_FINAL_DWGS";
			break;
		case "REJECTLUPAPP":
			mappedField = "P_D_PERM_LUP_JX_REJECT_OF_APPLIC";
			break;
		case "REJECTLUP":
			mappedField = "P_D_PERM_LUP_JX_REJECT_OF_PERMIT";
			break;
		case "RENEWLUPREQUEST":
			mappedField = "P_D_REOPS_LUP_RENEWAL_REQEUST";
			break;
		case "LANDSURVEY":
			mappedField = "P_D_AE_LANDSURVEY";
			break;
		case "LUP":
			mappedField = "P_D_PERM_LAND_USE_PERMIT";
			break;
		case "LUPAPP":
			mappedField = "P_D_PERM_LAND_USE_PERMIT_APP";
			break;
		case "NEPAFULL":
			mappedField = "P_D_REG_NEPA";
			break;
		case "NEPACHKLST":
			mappedField = "P_D_REG_NEPA_CHECKLIST";
			break;
		case "NEPASCREEN":
			mappedField = "P_D_REG_NEPA_SCREEN_ANALYSIS";
			break;
		case "NTP":
			mappedField = "P_D_CONST_NTP_CHECKLIST";
			break;
		case "PERMITRTI":
			mappedField = "P_D_AZP_READY_TO_ISSUE";
			break;
		case "PHOTOSIMS":
			mappedField = "P_D_AE_PHOTOSIMMS";
			break;
		case "POLEAGMNT":
			mappedField = "P_D_REOPS_POLE_ATTACHMENT_AGRMNT";
			break;
		case "POLEREADY":
			mappedField = "P_D_CONST_POLE_READY_ACCPT";
			break;
		case "ROWAGMNT":
			mappedField = "P_D_REOPS_ROW_AGRMNT";
			break;
		case "ROWSURVEY":
			mappedField = "P_D_AE_ROWSURVEY";
			break;
		case "SCIPAPPROVAL":
			mappedField = "P_D_SCIP_APPROV_FROM_SPRINT";
			break;
		case "SCIPPIC":
			mappedField = "P_D_SCIP_PHOTOS";
			break;
		case "SHPORESP":
			mappedField = "P_D_REG_SHPO_INITIAL_RESPONSE";
			break;
		case "SHPOPUBNOTICE":
			mappedField = "P_D_REG_SHPO_PUBLIC_NOTICE";
			break;
		case "SHPOPACKAGE":
			mappedField = "P_D_REG_SHPO_SUBMITTAL";
			break;
		case "SITEACCEPTANCE":
			mappedField = "P_D_CONST_SITE_ACCEPT_CHKLIST";
			break;
		case "POLEDWGS":
			mappedField = "P_D_AE_POLEDWGS";
			break;
		case "STRUCTURAL":
			mappedField = "P_D_AE_STRUCTURAL_EVAL";
			break;
		case "TRIBALTCNS":
			mappedField = "P_D_REG_TCNS_INITIAL_TRIBE_RESP";
			break;
		case "TRIBALPACKAGE":
			mappedField = "P_D_REG_TCNS_PPKG_FROM_MOBILITIE";
			break;
		case "TRIBALRESP":
			mappedField = "P_D_REG_TCNS_TRIBE_RESPONSE_TO_PKK";
			break;
		case "TOWAIRSCREEN":
			mappedField = "P_D_REG_TOWAIR_SCREEN";
			break;
		case "UTILITYSURVEY":
			mappedField = "P_D_AE_UTILITYSURVEY";
			break;
		case "ZPDELCONF":
			mappedField = "P_D_PERM_ZP_APP_CONFIRM";
			break;
		case "ZPCOVLET":
			mappedField = "P_D_PERM_ZP_APP_CVR_LETTER";
			break;
		case "ZPAPP":
			mappedField = "P_D_PERM_ZP_APPLIC";
			break;
		case "ZPDWG":
			mappedField = "P_D_DWG_DWGS_ZONING_PMT";
			break;
		case "ESCALATEZP":
			mappedField = "P_D_PERM_ZP_ESCAL";
			break;
		case "ZP":
			mappedField = "P_D_PERM_ZP_FINAL_APPROVED";
			break;
		case "ZPDWGFINAL":
			mappedField = "P_D_DWG_DWGS_ZONING_PMT_FINAL";
			break;
		case "REJECTZPAPP":
			mappedField = "P_D_PERM_ZP_JX_REJECT_OF_APPLIC";
			break;
		case "REJECTZP":
			mappedField = "P_D_PERM_ZP_JX_REJECT_OF_PERMIT";
			break;

		default:
			mappedField = "";
		}

		return mappedField;
	}

	private String download(String documentPath, Logger logger) {
		String encodedString = "";
		try {
			URL url = new URL(documentPath);
			HttpURLConnection connection = (HttpURLConnection) url
					.openConnection();
			connection.setRequestMethod("GET");
			InputStream in = connection.getInputStream();

			byte[] bytes = IOUtils.toByteArray(in);
			encodedString = Base64.getEncoder().encodeToString(bytes);

		} catch (Exception e) {
			logger.error("Error converting the document in SMS to a base 64 encoded string");
			e.printStackTrace();
		}
		return encodedString;
	}

	private void sendErrorEmail(String errorMessage, Logger logger) {
		final String username = "SMSOVAlerts@mobilitie.com";
		final String password = "Password1!";
		Properties props = new Properties();
		props.put("mail.smtp.auth", "true");
		props.put("mail.smtp.starttls.enable", "true");
		props.put("mail.smtp.host", "smtp.office365.com");
		props.put("mail.smtp.port", "587");

		Session session = Session.getInstance(props,
				new javax.mail.Authenticator() {
					protected PasswordAuthentication getPasswordAuthentication() {
						return new PasswordAuthentication(username, password);
					}
				});

		try {
			Calendar calendar = Calendar.getInstance();
			Date currentDate = calendar.getTime();
			StringBuilder textForEmail = new StringBuilder(1000);
			Message message = new MimeMessage(session);

			// TODO Move this out
			message.setFrom(new InternetAddress("SMSOVAlerts@mobilitie.com"));

			// TODO move this out
			message.setRecipients(
					Message.RecipientType.TO,
					InternetAddress
							.parse("kreddy@mobilitie.com,sareh.salamipour@mobilitie.com,bryce@mobilitie.com"));
			message.setSubject("Document Transfer from SMS to MTRAC3.0 - Failed to Complete Execution"
					+ "\n");
			textForEmail.append("The script was executed at:" + currentDate
					+ "\n");
			textForEmail.append("\n");
			textForEmail.append("The error message is:" + "\n");
			textForEmail.append("\n");
			textForEmail.append(errorMessage);
			message.setText(textForEmail.toString());
			Transport.send(message);
			logger.debug("Error Email sent ");
		} catch (MessagingException e) {
			throw new RuntimeException(e);
		}

	}

	private String convertListTOString(List<String> processedAgmtCodes) {

		StringBuilder csvString = new StringBuilder(1000);
		for (int i = 0; i < processedAgmtCodes.size(); i++) {
			csvString.append(processedAgmtCodes.get(i) + "\n");
		}

		return csvString.toString();
	}

	private int postDataToMTRAC(JSONObject obj, String trackorKey, Logger logger) {
		Client c = Client.create();
		c.addFilter(new HTTPBasicAuthFilter("apisms", "QsXeGfofsO"));
		WebResource webResource = c
				.resource("https://mtrac.mobilitie.com/api/v2/trackor_type/Projects");
		MultivaluedMap<String, String> params = new MultivaluedMapImpl();
		trackorKey = trackorKey + "-N16.1";
		params.add("TRACKOR_KEY", trackorKey);
		ClientResponse response = webResource.queryParams(params)
				.accept("application/json")
				.put(ClientResponse.class, obj.toString());
		String output = response.getEntity(String.class);
		logger.debug("For the candidate:" + trackorKey + "\t" + output + "\t"
				+ "The responsecode is:" + response.getStatus() + "\t");
		return response.getStatus();
	}

	private Logger createLogger() {

		PatternLayout layout = new PatternLayout();
		String conversionPattern = "[%p] %d %c %M - %m%n";
		layout.setConversionPattern(conversionPattern);
		// creates daily rolling file appender
		DailyRollingFileAppender rollingAppender = new DailyRollingFileAppender();
		rollingAppender.setFile("C:/Integrations/Logs/DocumentTransfer.log");
		rollingAppender.setDatePattern("'.'yyyy-MM-dd");
		rollingAppender.setLayout(layout);
		rollingAppender.activateOptions();

		// configures the root logger
		Logger rootLogger = Logger.getRootLogger();
		rootLogger.setLevel(Level.DEBUG);
		rootLogger.addAppender(rollingAppender);

		// creates a custom logger and log messages
		Logger logger = Logger.getLogger(DocumentTransferJob.class);
		return logger;

	}
}
