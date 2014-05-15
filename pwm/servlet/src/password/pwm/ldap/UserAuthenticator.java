/*
 * Password Management Servlets (PWM)
 * http://code.google.com/p/pwm/
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2014 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.ldap;

import com.novell.ldapchai.ChaiConstant;
import com.novell.ldapchai.ChaiFactory;
import com.novell.ldapchai.ChaiUser;
import com.novell.ldapchai.exception.*;
import com.novell.ldapchai.impl.oracleds.entry.OracleDSEntries;
import com.novell.ldapchai.provider.ChaiProvider;
import password.pwm.PwmApplication;
import password.pwm.PwmConstants;
import password.pwm.PwmPasswordPolicy;
import password.pwm.PwmSession;
import password.pwm.bean.SessionStateBean;
import password.pwm.bean.UserIdentity;
import password.pwm.bean.UserInfoBean;
import password.pwm.config.PwmSetting;
import password.pwm.error.ErrorInformation;
import password.pwm.error.PwmError;
import password.pwm.error.PwmOperationalException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.event.AuditEvent;
import password.pwm.util.PwmLogger;
import password.pwm.util.RandomPasswordGenerator;
import password.pwm.util.TimeDuration;
import password.pwm.util.intruder.IntruderManager;
import password.pwm.util.intruder.RecordType;
import password.pwm.util.operations.PasswordUtility;
import password.pwm.util.stats.Statistic;
import password.pwm.util.stats.StatisticsManager;

import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;

public class UserAuthenticator {
    private static final PwmLogger LOGGER = PwmLogger.getLogger(UserAuthenticator.class.getName());
    private static final String ORACLE_ATTR_PW_ALLOW_CHG_TIME = "passwordAllowChangeTime";

    public static void authenticateUser(
            final String username,
            final String password,
            final String context,
            final String profile,
            final PwmSession pwmSession,
            final PwmApplication pwmApplication,
            final boolean secure
    )
            throws ChaiUnavailableException, PwmUnrecoverableException, PwmOperationalException
    {
        pwmApplication.getIntruderManager().check(RecordType.USERNAME, username);
        final UserIdentity userIdentity;
        try {
            final UserSearchEngine userSearchEngine = new UserSearchEngine(pwmApplication);
            userIdentity = userSearchEngine.resolveUsername(pwmSession, username, context, profile);
        } catch (PwmOperationalException e) {
            pwmApplication.getStatisticsManager().incrementValue(Statistic.AUTHENTICATION_FAILURES);
            pwmApplication.getIntruderManager().mark(RecordType.USERNAME, username, pwmSession);
            pwmApplication.getIntruderManager().convenience().markAddressAndSession(pwmSession);
            throw e;
        }
        authenticateUser(userIdentity, password, pwmSession, pwmApplication, secure);
    }


    public static void authenticateUser(
            final UserIdentity userIdentity,
            final String password,
            final PwmSession pwmSession,
            final PwmApplication pwmApplication,
            final boolean secure
    )
            throws ChaiUnavailableException, PwmUnrecoverableException, PwmOperationalException
    {
        final long methodStartTime = System.currentTimeMillis();
        final StatisticsManager statisticsManager = pwmApplication.getStatisticsManager();
        final IntruderManager intruderManager = pwmApplication.getIntruderManager();

        intruderManager.convenience().checkUserIdentity(userIdentity);

        boolean allowBindAsUser = true;
        try {
            testCredentials(userIdentity, password, pwmApplication, pwmSession);
        } catch (PwmOperationalException e) {
            boolean permitAuthDespiteError = false;
            final ChaiProvider.DIRECTORY_VENDOR vendor = pwmApplication.getProxyChaiProvider(userIdentity.getLdapProfileID()).getDirectoryVendor();
            if (PwmError.PASSWORD_NEW_PASSWORD_REQUIRED == e.getError()) {
                if (vendor == ChaiProvider.DIRECTORY_VENDOR.MICROSOFT_ACTIVE_DIRECTORY) {
                    if (pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.AD_ALLOW_AUTH_REQUIRE_NEW_PWD)) {
                        LOGGER.info(
                                "auth bind failed, but will allow login due to 'must change password on next login AD error', error: " + e.getErrorInformation().toDebugStr());
                        allowBindAsUser = false;
                        permitAuthDespiteError = true;
                    }
                } else if (vendor == ChaiProvider.DIRECTORY_VENDOR.ORACLE_DS) {
                    if (pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.ORACLE_DS_ALLOW_AUTH_REQUIRE_NEW_PWD)) {
                        LOGGER.info(
                                "auth bind failed, but will allow login due to 'pwdReset' user attribute, error: " + e.getErrorInformation().toDebugStr());
                        allowBindAsUser = false;
                        permitAuthDespiteError = true;
                    }
                }
            } else if (PwmError.PASSWORD_EXPIRED == e.getError()) { // handle ad case where password is expired
                if (vendor == ChaiProvider.DIRECTORY_VENDOR.MICROSOFT_ACTIVE_DIRECTORY) {
                    if (pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.AD_ALLOW_AUTH_REQUIRE_NEW_PWD)) {
                        if (!pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.AD_ALLOW_AUTH_EXPIRED)) {
                            throw e;
                        }
                        LOGGER.info(
                                "auth bind failed, but will allow login due to 'password expired AD error', error: " + e.getErrorInformation().toDebugStr());
                        allowBindAsUser = false;
                        permitAuthDespiteError = true;
                    }
                }
            }

            if (!permitAuthDespiteError) {    // auth failed, presumably due to wrong password.
                LOGGER.info(pwmSession, "login attempt for " + userIdentity + " failed: " + e.getErrorInformation().toDebugStr());
                statisticsManager.incrementValue(Statistic.AUTHENTICATION_FAILURES);
                intruderManager.convenience().markUserIdentity(userIdentity, pwmSession);
                pwmApplication.getIntruderManager().convenience().markAddressAndSession(pwmSession);
                throw e;
            }
        }

        final StringBuilder debugMsg = new StringBuilder();
        debugMsg.append("successful ");
        debugMsg.append(secure ? "ssl" : "plaintext");
        debugMsg.append(" authentication for ").append(userIdentity);
        debugMsg.append(" (").append(TimeDuration.fromCurrent(methodStartTime).asCompactString()).append(")");
        LOGGER.info(pwmSession, debugMsg);
        statisticsManager.incrementValue(Statistic.AUTHENTICATIONS);
        statisticsManager.updateEps(Statistic.EpsType.AUTHENTICATION, 1);

        postAuthenticationSequence(pwmApplication, pwmSession, userIdentity, password, allowBindAsUser, methodStartTime);
        final UserInfoBean.AuthenticationType authenticationType = allowBindAsUser ? UserInfoBean.AuthenticationType.AUTHENTICATED : UserInfoBean.AuthenticationType.AUTH_BIND_INHIBIT;
        pwmSession.getUserInfoBean().setAuthenticationType(authenticationType);
        LOGGER.debug(pwmSession, "user authenticated with authentication type: " + authenticationType);
        pwmApplication.getAuditManager().submit(pwmApplication.getAuditManager().createUserAuditRecord(
                AuditEvent.AUTHENTICATE,
                pwmSession.getUserInfoBean().getUserIdentity(),
                new Date(),
                authenticationType.toString(),
                pwmSession.getUserInfoBean().getUserIdentity(),
                pwmSession.getSessionStateBean().getSrcAddress(),
                pwmSession.getSessionStateBean().getSrcHostname()
        ));
    }

    public static void authUserWithUnknownPassword(
            final String username,
            final PwmSession pwmSession,
            final PwmApplication pwmApplication,
            final boolean secure,
            final UserInfoBean.AuthenticationType authenticationType
    )
            throws ChaiUnavailableException, ImpossiblePasswordPolicyException, PwmUnrecoverableException, PwmOperationalException
    {
        final UserSearchEngine userSearchEngine = new UserSearchEngine(pwmApplication);
        final UserIdentity userIdentity = userSearchEngine.resolveUsername(pwmSession, username, null, null);
        authUserWithUnknownPassword(userIdentity, pwmSession, pwmApplication, secure, authenticationType);
    }

    /**
     * Caused by various modules, this method will cause the PWM session to become
     * authenticated without having the users password.  Depending on configuration
     * and nmas availability this may cause the users ldap password to be set to a random
     * value.  Typically the user would be redirectde to the change password servlet immediately
     * after this method is called.
     * <p/>
     * It is up to the caller to insure that any security requirements have been met BEFORE calling
     * this method, such as validiting challenge/responses.
     *
     * @param userIdentity    User to authenticate
     * @param pwmSession A PwmSession instance
     * @throws com.novell.ldapchai.exception.ChaiUnavailableException
     *          If ldap becomes unreachable
     * @throws password.pwm.error.PwmUnrecoverableException
     *          If there is some reason the session can't be authenticated
     *          If the user's password policy is determined to be impossible to satisfy
     * @throws com.novell.ldapchai.exception.ImpossiblePasswordPolicyException
     *          if the temporary password generated can't be due to an impossible policy
     */
    public static void authUserWithUnknownPassword(
            final UserIdentity userIdentity,
            final PwmSession pwmSession,
            final PwmApplication pwmApplication,
            final boolean secure,
            final UserInfoBean.AuthenticationType authenticationType
    )
            throws ChaiUnavailableException, ImpossiblePasswordPolicyException, PwmUnrecoverableException {
        LOGGER.trace(pwmSession, "beginning auth processes for user with unknown password");
        long startAuthenticationTimestamp = System.currentTimeMillis();

        if (userIdentity == null || userIdentity.getUserDN() == null || userIdentity.getUserDN().length() < 1) {
            throw new NullPointerException("invalid user (null)");
        }

        final ChaiProvider chaiProvider = pwmApplication.getProxyChaiProvider(userIdentity.getLdapProfileID());
        final ChaiUser chaiUser = ChaiFactory.createChaiUser(userIdentity.getUserDN(), chaiProvider);

        // use chai (nmas) to retrieve user password
        if (pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.EDIRECTORY_READ_USER_PWD)) {
            String currentPass = null;
            try {
                final String readPassword = chaiUser.readPassword();
                if (readPassword != null && readPassword.length() > 0) {
                    currentPass = readPassword;
                    LOGGER.debug(pwmSession, "successfully retrieved user's current password from ldap, now conducting standard authentication");
                }
            } catch (Exception e) {
                LOGGER.error(pwmSession, "unable to retrieve user password from ldap: " + e.getMessage());
            }

            // actually do the authentication since we have user pw.
            if (currentPass != null && currentPass.length() > 0) {
                try {
                    authenticateUser(userIdentity.getUserDN(), currentPass, null, null, pwmSession, pwmApplication, secure);
                    return;
                } catch (PwmOperationalException e) {
                    final String errorStr = "unable to authenticate with admin retrieved password, check proxy rights, ldap logs; error: " + e.getMessage();
                    LOGGER.error(pwmSession,errorStr);
                    throw new PwmUnrecoverableException(new ErrorInformation(PwmError.ERROR_BAD_SESSION_PASSWORD, errorStr));
                }
            }
        } else {
            LOGGER.trace(pwmSession, "skipping attempt to read user password, option disabled");
        }


        final boolean configAlwaysUseProxy = pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.AD_USE_PROXY_FOR_FORGOTTEN);

        // try setting a random password on the account to authenticate.
        if (!configAlwaysUseProxy && authenticationType == UserInfoBean.AuthenticationType.AUTH_FROM_FORGOTTEN ) {
            LOGGER.debug(pwmSession, "attempting to set temporary random password");
            String currentPass = null;
            try {
                final PwmPasswordPolicy passwordPolicy = PasswordUtility.readPasswordPolicyForUser(pwmApplication, pwmSession, userIdentity, chaiUser, pwmSession.getSessionStateBean().getLocale());
                pwmSession.getUserInfoBean().setPasswordPolicy(passwordPolicy);

                // createSharedHistoryManager random password for user
                RandomPasswordGenerator.RandomGeneratorConfig randomGeneratorConfig = new RandomPasswordGenerator.RandomGeneratorConfig();
                randomGeneratorConfig.setSeedlistPhrases(RandomPasswordGenerator.DEFAULT_SEED_PHRASES);
                randomGeneratorConfig.setPasswordPolicy(pwmSession.getUserInfoBean().getPasswordPolicy());

                currentPass = RandomPasswordGenerator.createRandomPassword(pwmSession, randomGeneratorConfig, pwmApplication);

                try {
                    final String oracleDS_PrePasswordAllowChangeTime = oraclePreUnknownPwAuthHandler(
                            pwmApplication, pwmSession, chaiProvider, chaiUser
                    );

                    // write the random password for the user.
                    chaiUser.setPassword(currentPass);

                    oraclePostUnknownPwAuthHandler(
                            pwmApplication, pwmSession, chaiProvider, chaiUser, oracleDS_PrePasswordAllowChangeTime
                    );

                    LOGGER.info(pwmSession, "user " + userIdentity + " password has been set to random value to use for user authentication");
                    // force a user password change.
                    pwmSession.getUserInfoBean().setRequiresNewPassword(true);
                } catch (ChaiPasswordPolicyException e) {
                    final String errorStr = "error setting random password for user " + userIdentity + " " + e.getMessage();
                    LOGGER.warn(pwmSession, errorStr);
                    throw new PwmUnrecoverableException(new ErrorInformation(PwmError.ERROR_BAD_SESSION_PASSWORD, errorStr));
                } catch (ChaiOperationException e) {
                    final String errorStr = "error setting random password for user " + userIdentity + " " + e.getMessage();
                    LOGGER.warn(pwmSession, errorStr);
                    throw new PwmUnrecoverableException(new ErrorInformation(PwmError.ERROR_BAD_SESSION_PASSWORD, errorStr));
                }
            } finally {
                pwmSession.getUserInfoBean().setPasswordPolicy(PwmPasswordPolicy.defaultPolicy());
            }

            // actually do the authentication since we have user pw.
            try {
                authenticateUser(userIdentity, currentPass, pwmSession, pwmApplication, secure);

                // close any outstanding ldap connections (since they cache the old password)
                final boolean vendorIsOracleDS = ChaiProvider.DIRECTORY_VENDOR.ORACLE_DS == pwmApplication.getProxyChaiProvider(userIdentity.getLdapProfileID()).getDirectoryVendor();
                if (!vendorIsOracleDS ) {
                    //leave the connection open for oracleDS.  The connection is actually successfully bound at this point, but will fail
                    //for everything but a change password operation.
                    pwmSession.getSessionManager().closeConnections();
                }

                return;
            } catch (PwmOperationalException e) {
                final String errorStr = "unable to authenticate with temporary password, check proxy rights, ldap logs; error: " + e.getMessage();
                LOGGER.error(pwmSession,errorStr);
                throw new PwmUnrecoverableException(new ErrorInformation(PwmError.ERROR_BAD_SESSION_PASSWORD, errorStr));
            }
        }


        postAuthenticationSequence(pwmApplication, pwmSession, userIdentity, null, false, startAuthenticationTimestamp);

        pwmSession.getUserInfoBean().setAuthenticationType(authenticationType);
        LOGGER.debug(pwmSession,"user authenticated with authentication type: " + authenticationType);

        pwmApplication.getAuditManager().submit(pwmApplication.getAuditManager().createUserAuditRecord(
                AuditEvent.AUTHENTICATE,
                pwmSession.getUserInfoBean().getUserIdentity(),
                new Date(),
                authenticationType.toString(),
                pwmSession.getUserInfoBean().getUserIdentity(),
                pwmSession.getSessionStateBean().getSrcAddress(),
                pwmSession.getSessionStateBean().getSrcHostname()
        ));
    }

    public static void testCredentials(
            final UserIdentity userIdentity,
            final String password,
            final PwmApplication pwmApplication,
            final PwmSession pwmSession
    )
            throws ChaiUnavailableException, PwmUnrecoverableException, PwmOperationalException
    {
        LOGGER.trace(pwmSession, "beginning testCredentials process");

        if (userIdentity == null || userIdentity.getUserDN() == null || userIdentity.getUserDN().length() < 1) {
            final String errorMsg = "attempt to authenticate with null userDN";
            LOGGER.debug(pwmSession, errorMsg);
            throw new PwmOperationalException(new ErrorInformation(PwmError.ERROR_WRONGPASSWORD,errorMsg));
        }

        if (password == null || password.length() < 1) {
            final String errorMsg = "attempt to authenticate with null password";
            LOGGER.debug(pwmSession, errorMsg);
            throw new PwmOperationalException(new ErrorInformation(PwmError.ERROR_WRONGPASSWORD,errorMsg));
        }

        //try authenticating the user using a normal ldap BIND operation.
        LOGGER.trace(pwmSession, "attempting authentication using ldap BIND");
        try {
            //get a provider using the user's DN and password.
            final ChaiProvider testProvider = pwmSession.getSessionManager().getChaiProvider(pwmApplication, userIdentity, password);

            //issue a read operation to trigger a bind.
            testProvider.readStringAttribute(userIdentity.getUserDN(), ChaiConstant.ATTR_LDAP_OBJECTCLASS);
        } catch (ChaiException e) {
            if (e.getErrorCode() != null && e.getErrorCode() == ChaiError.INTRUDER_LOCKOUT) {
                final String errorMsg = "intruder lockout detected for user " + userIdentity + " marking session as locked out: " + e.getMessage();
                final ErrorInformation errorInformation = new ErrorInformation(PwmError.ERROR_INTRUDER_LDAP, errorMsg);
                LOGGER.warn(pwmSession, errorInformation.toDebugStr());
                pwmSession.getSessionStateBean().setSessionError(errorInformation);
                throw new PwmUnrecoverableException(errorInformation);
            }
            final PwmError pwmError = PwmError.forChaiError(e.getErrorCode());
            final ErrorInformation errorInformation;
            if (pwmError != null && PwmError.ERROR_UNKNOWN != pwmError) {
                errorInformation = new ErrorInformation(pwmError, e.getMessage());
            } else {
                errorInformation = new ErrorInformation(PwmError.ERROR_WRONGPASSWORD, "ldap error during password check: " + e.getMessage());
            }
            LOGGER.debug(pwmSession, errorInformation.toDebugStr());
            throw new PwmOperationalException(errorInformation);
        }
    }

    public static void simulateBadPassword(
            final UserIdentity userIdentity,
            final PwmApplication pwmApplication,
            final PwmSession pwmSession
    )
            throws PwmUnrecoverableException
    {
        if (!pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.SECURITY_SIMULATE_LDAP_BAD_PASSWORD)) {
            return;
        } else {
            LOGGER.trace(pwmSession, "performing bad-password login attempt against ldap directory as a result of forgotten password recovery invalid attempt against " + userIdentity);
        }

        if (userIdentity == null || userIdentity.getUserDN() == null || userIdentity.getUserDN().length() < 1) {
            LOGGER.error(pwmSession, "attempt to simulateBadPassword with null userDN");
            return;
        }

        LOGGER.trace(pwmSession, "beginning simulateBadPassword process");

        final String bogusPassword = PwmConstants.DEFAULT_BAD_PASSWORD_ATTEMPT;

        //try authenticating the user using a normal ldap BIND operation.
        LOGGER.trace(pwmSession, "attempting authentication using ldap BIND");
        try {
            //get a provider using the user's DN and password.
            final ChaiProvider testProvider = pwmSession.getSessionManager().getChaiProvider(pwmApplication, userIdentity, bogusPassword);

            //issue a read operation to trigger a bind.
            testProvider.readStringAttribute(userIdentity.getUserDN(), ChaiConstant.ATTR_LDAP_OBJECTCLASS);

            LOGGER.warn(pwmSession, "bad-password login attempt succeeded for " + userIdentity + "! (this should always fail)");
        } catch (ChaiException e) {
            if (e.getErrorCode() == ChaiError.PASSWORD_BADPASSWORD) {
                LOGGER.trace(pwmSession, "bad-password login simulation succeeded for; " + userIdentity + " result: " + e.getMessage());
            } else {
                LOGGER.debug(pwmSession, "unexpected error during bad-password login attempt for " + userIdentity + "; result: " + e.getMessage());
            }
        }
    }


    private static void postAuthenticationSequence(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final UserIdentity userIdentity,
            final String userPassword,
            final boolean bindAsUser,
            final long startAuthenticationTimestamp
    )
            throws PwmUnrecoverableException, ChaiUnavailableException
    {
        final IntruderManager intruderManager = pwmApplication.getIntruderManager();
        final SessionStateBean ssBean = pwmSession.getSessionStateBean();

        if (pwmApplication.getStatisticsManager() != null) {
            final StatisticsManager statisticsManager = pwmApplication.getStatisticsManager();

            pwmApplication.getStatisticsManager().updateAverageValue(Statistic.AVG_AUTHENTICATION_TIME, TimeDuration.fromCurrent(startAuthenticationTimestamp).getTotalMilliseconds());

            if (pwmSession.getUserInfoBean().getPasswordState().isWarnPeriod()) {
                statisticsManager.incrementValue(Statistic.AUTHENTICATION_EXPIRED_WARNING);
            } else if (pwmSession.getUserInfoBean().getPasswordState().isPreExpired()) {
                statisticsManager.incrementValue(Statistic.AUTHENTICATION_PRE_EXPIRED);
            } else if (pwmSession.getUserInfoBean().getPasswordState().isExpired()) {
                statisticsManager.incrementValue(Statistic.AUTHENTICATION_EXPIRED);
            }
        }

        // auth succeed
        ssBean.setAuthenticated(true);

        // update the actor user info bean
        final UserInfoBean userInfoBean = pwmSession.getUserInfoBean();
        final UserStatusReader userStatusReader = new UserStatusReader(pwmApplication);
        if (!bindAsUser) {
            userStatusReader.populateUserInfoBean(
                    pwmSession,
                    userInfoBean,
                    ssBean.getLocale(),
                    userIdentity,
                    userPassword,
                    pwmApplication.getProxyChaiProvider(userIdentity.getLdapProfileID())
            );
        } else {
            userStatusReader.populateActorUserInfoBean(
                    pwmSession,
                    userIdentity,
                    userPassword);
        }

        //notify the intruder manager with a successful login
        intruderManager.clear(RecordType.USERNAME, pwmSession.getUserInfoBean().getUsername());
        intruderManager.convenience().clearUserIdentity(userIdentity);
        intruderManager.convenience().clearAddressAndSession(pwmSession);

        //mark the auth time
        userInfoBean.setLocalAuthTime(new Date());

        //clear permission cache - needs rechecking after login
        LOGGER.debug("Clearing permission cache");
        userInfoBean.clearPermissions();
    }

    private static String oraclePreUnknownPwAuthHandler(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final ChaiProvider chaiProvider,
            final ChaiUser chaiUser
    )
            throws PwmUnrecoverableException, ChaiUnavailableException, ChaiOperationException
    {
        if (!pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.ORACLE_DS_ENABLE_MANIP_ALLOWCHANGETIME)) {
            return null;
        }

        if (ChaiProvider.DIRECTORY_VENDOR.ORACLE_DS != chaiUser.getChaiProvider().getDirectoryVendor()) {
            return null;
        }

        // oracle DS special case: passwordAllowChangeTime handler
        final String oracleDS_PrePasswordAllowChangeTime = chaiProvider.readStringAttribute(
                chaiUser.getEntryDN(),
                ORACLE_ATTR_PW_ALLOW_CHG_TIME);
        LOGGER.trace(pwmSession,
                "read OracleDS value of passwordAllowChangeTime value=" + oracleDS_PrePasswordAllowChangeTime);

        if (oracleDS_PrePasswordAllowChangeTime != null && !oracleDS_PrePasswordAllowChangeTime.isEmpty()) {
            final Date date = OracleDSEntries.convertZuluToDate(oracleDS_PrePasswordAllowChangeTime);
            if (new Date().before(date)) {
                final String errorMsg = "change not permitted until " + PwmConstants.DEFAULT_DATETIME_FORMAT.format(
                        date);
                throw new PwmUnrecoverableException(
                        new ErrorInformation(PwmError.PASSWORD_TOO_SOON, errorMsg));
            }
        }

        return oracleDS_PrePasswordAllowChangeTime;
    }

    private static void oraclePostUnknownPwAuthHandler(
            final PwmApplication pwmApplication,
            final PwmSession pwmSession,
            final ChaiProvider chaiProvider,
            final ChaiUser chaiUser,
            final String oracleDS_PrePasswordAllowChangeTime
    )
            throws ChaiUnavailableException, ChaiOperationException
    {
        if (!pwmApplication.getConfig().readSettingAsBoolean(PwmSetting.ORACLE_DS_ENABLE_MANIP_ALLOWCHANGETIME)) {
            return;
        }

        // oracle DS special case: passwordAllowChangeTime handler
        if (ChaiProvider.DIRECTORY_VENDOR.ORACLE_DS != chaiUser.getChaiProvider().getDirectoryVendor()) {
            return;
        }

        if (oracleDS_PrePasswordAllowChangeTime != null && !oracleDS_PrePasswordAllowChangeTime.isEmpty()) {
            // write back the original pre-password allow change time.
            final Set<String> values = new HashSet<String>(
                    Collections.singletonList(oracleDS_PrePasswordAllowChangeTime));
            chaiProvider.writeStringAttribute(chaiUser.getEntryDN(), ORACLE_ATTR_PW_ALLOW_CHG_TIME,
                    values,
                    true);
            LOGGER.trace(pwmSession,
                    "re-wrote passwordAllowChangeTime attribute to user " + chaiUser.getEntryDN() + ", value=" + oracleDS_PrePasswordAllowChangeTime);
        } else {
            final String oracleDS_PostPasswordAllowChangeTime = chaiProvider.readStringAttribute(
                    chaiUser.getEntryDN(),
                    ORACLE_ATTR_PW_ALLOW_CHG_TIME);
            if (oracleDS_PostPasswordAllowChangeTime != null && !oracleDS_PostPasswordAllowChangeTime.isEmpty()) {
                // password allow change time has appeared, but wasn't present previously, so delete it.
                LOGGER.trace(pwmSession,
                        "a new value for passwordAllowChangeTime attribute to user " + chaiUser.getEntryDN() + " has appeared, will remove");
                final Set<String> values = new HashSet<String>(
                        Collections.singletonList(OracleDSEntries.convertDateToZulu(new Date())));
                chaiProvider.writeStringAttribute(chaiUser.getEntryDN(), ORACLE_ATTR_PW_ALLOW_CHG_TIME,
                        values, true);
                LOGGER.trace(pwmSession,
                        "deleted attribute value for passwordAllowChangeTime attribute on user " + chaiUser.getEntryDN());
            }
        }
    }
}