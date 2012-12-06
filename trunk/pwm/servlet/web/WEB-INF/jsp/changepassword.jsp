<%--
  ~ Password Management Servlets (PWM)
  ~ http://code.google.com/p/pwm/
  ~
  ~ Copyright (c) 2006-2009 Novell, Inc.
  ~ Copyright (c) 2009-2012 The PWM Project
  ~
  ~ This program is free software; you can redistribute it and/or modify
  ~ it under the terms of the GNU General Public License as published by
  ~ the Free Software Foundation; either version 2 of the License, or
  ~ (at your option) any later version.
  ~
  ~ This program is distributed in the hope that it will be useful,
  ~ but WITHOUT ANY WARRANTY; without even the implied warranty of
  ~ MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  ~ GNU General Public License for more details.
  ~
  ~ You should have received a copy of the GNU General Public License
  ~ along with this program; if not, write to the Free Software
  ~ Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
  --%>

<!DOCTYPE html>
<%@ page import="password.pwm.config.PasswordStatus" %>
<%@ page language="java" session="true" isThreadSafe="true" contentType="text/html; charset=UTF-8" %>
<%@ taglib uri="pwm" prefix="pwm" %>
<% final PasswordStatus passwordStatus = PwmSession.getPwmSession(session).getUserInfoBean().getPasswordState(); %>
<html dir="<pwm:LocaleOrientation/>">
<%@ include file="fragment/header.jsp" %>
<body onload="pwmPageLoadHandler(); startupChangePasswordPage('<pwm:Display key="Display_PasswordPrompt"/>');" class="nihilo">
<script type="text/javascript"
        src="<%=request.getContextPath()%><pwm:url url='/public/resources/changepassword.js'/>"></script>
<div id="wrapper">
    <jsp:include page="fragment/header-body.jsp">
        <jsp:param name="pwm.PageName" value="Title_ChangePassword"/>
    </jsp:include>
    <div id="centerbody">
        <% if (passwordStatus.isExpired() || passwordStatus.isPreExpired() || passwordStatus.isViolatesPolicy()) { %>
        <h1><pwm:Display key="Display_PasswordExpired"/></h1><br/>
        <%-- <p/>You have <pwm:LdapValue name="loginGraceRemaining"/> remaining logins. --%>
        <% } %>
        <pwm:Display key="Display_ChangePassword"/>
        <div id="PasswordRequirements">
            <ul>
                <pwm:DisplayPasswordRequirements separator="</li>" prepend="<li>"/>
            </ul>
        </div>
        <% final String passwordPolicyChangeMessage = PwmSession.getPwmSession(session).getUserInfoBean().getPasswordPolicy().getRuleHelper().getChangeMessage(); %>
        <% if (passwordPolicyChangeMessage.length() > 1) { %>
        <p><%= passwordPolicyChangeMessage %>
        </p>
        <% } %>
        <p id="passwordGuide" style="visibility:hidden;">
            &nbsp;»&nbsp; <a href="#" onclick="showPasswordGuide();"><pwm:Display key="Display_ShowPasswordGuide"/></a>
        </p>
        <% if (ContextManager.getPwmApplication(session).getConfig() != null && ContextManager.getPwmApplication(session).getConfig().readSettingAsBoolean(PwmSetting.PASSWORD_SHOW_AUTOGEN)) { %>
        <p id="autoGeneratePassword" style="visibility:hidden;">
            &nbsp;»&nbsp; <a href="#" onclick="doRandomGeneration();"><pwm:Display key="Display_AutoGeneratedPassword"/></a>
        </p>
        <% } %>
        <br/>
        <%@ include file="fragment/message.jsp" %>
        <form action="<pwm:url url='ChangePassword'/>" method="post" enctype="application/x-www-form-urlencoded"
              onkeyup="validatePasswords(null);" onkeypress="checkForCapsLock(event)" onchange="validatePasswords(null);"
              onsubmit="handleChangePasswordSubmit(); handleFormSubmit('password_button',this);return false"
              onreset="handleFormClear();validatePasswords(null);setInputFocus();return false;" name="changePasswordForm"
              id="changePasswordForm">
            <table style="border:0">
                <% if (PwmSession.getPwmSession(session).getChangePasswordBean().isCurrentPasswordRequired()) { %>
                <tr>
                    <td style="border:0; width:75%">
                        <h2><label for="currentPassword"><pwm:Display key="Field_CurrentPassword"/></label></h2>
                        <input type="password" name="currentPassword" id="currentPassword" class="changepasswordfield"/>
                    </td>
                    <td style="border:0; width:15%">
                        &nbsp;
                    </td>
                    <td style="border:0; width:10%">&nbsp;</td>
                </tr>
                <% } %>
                <tr>
                    <td style="border:0; width:75%">
                        <h2><label for="password1"><pwm:Display key="Field_NewPassword"/></label></h2>
                        <input type="password" name="password1" id="password1" class="changepasswordfield"/>
                    </td>
                    <td style="border:0; width:15%">
                        <% if (ContextManager.getPwmApplication(session).getConfig() != null && ContextManager.getPwmApplication(session).getConfig().readSettingAsBoolean(PwmSetting.PASSWORD_SHOW_STRENGTH_METER)) { %>
                        <div id="strengthBox" style="visibility:hidden;">
                            <div id="strengthLabel" style="padding-top:40px;">
                                <pwm:Display key="Display_StrengthMeter"/>
                            </div>
                            <div class="progress-container" style="margin-bottom:10px">
                                <div id="strengthBar" style="width: 0">&nbsp;</div>
                            </div>
                        </div>
                        <script type="text/javascript">
                            require(["dijit/Tooltip","dojo/domReady!"],function(Tooltip){
                                new Tooltip({
                                    connectId: ["strengthBox"],
                                    label: '<div style="width: 350px">' + PWM_STRINGS['Tooltip_PasswordStrength'] + '</div>'
                                });
                            });
                        </script>
                        <% } %>
                    </td>
                    <td style="border:0; width:10%">&nbsp;</td>
                </tr>
                <tr>
                    <td style="border:0; width:75%">
                        <h2><label for="password2"><pwm:Display key="Field_ConfirmPassword"/></label></h2>
                        <input type="password" name="password2" id="password2" class="changepasswordfield"/>
                    </td>
                    <td style="border:0; width:15%">
                        <%-- confirmation mark [not shown initially, enabled by javascript; see also changepassword.js:markConfirmationMark() --%>
                        <div style="padding-top:45px;">
                            <img style="visibility:hidden;" id="confirmCheckMark" alt="checkMark" height="15" width="15"
                                 src="<%=request.getContextPath()%><pwm:url url='/public/resources/greenCheck.png'/>">
                            <img style="visibility:hidden;" id="confirmCrossMark" alt="crossMark" height="15" width="15"
                                 src="<%=request.getContextPath()%><pwm:url url='/public/resources/redX.png'/>">
                        </div>
                    </td>
                    <td style="border:0; width:10%">&nbsp;</td>
                </tr>
            </table>

            <div id="buttonbar" style="width:100%">
                <input type="hidden" name="processAction" value="change"/>
                <input type="submit" name="change" class="btn"
                       id="password_button"
                       value="<pwm:Display key="Button_ChangePassword"/>"/>
                <% if (ContextManager.getPwmApplication(session).getConfig().readSettingAsBoolean(password.pwm.config.PwmSetting.DISPLAY_RESET_BUTTON)) { %>
                <input type="reset" name="reset" class="btn"
                       value="<pwm:Display key="Button_Reset"/>"/>
                <% } %>
                <input type="hidden" name="hideButton" class="btn"
                       value="<pwm:Display key="Button_Show"/>"
                       onclick="toggleMaskPasswords()" id="hide_button"/>
                <% if (!passwordStatus.isExpired() && !passwordStatus.isPreExpired() && !passwordStatus.isViolatesPolicy() && !PwmSession.getPwmSession(session).getUserInfoBean().isAuthFromUnknownPw()) { %>
                <% if (ContextManager.getPwmApplication(session).getConfig().readSettingAsBoolean(password.pwm.config.PwmSetting.DISPLAY_CANCEL_BUTTON)) { %>
                <button style="visibility:hidden;" name="button" class="btn" id="button_cancel"
                        onclick="window.location='<%=request.getContextPath()%>/public/<pwm:url url='CommandServlet'/>?processAction=continue';return false">
                    <pwm:Display key="Button_Cancel"/>
                </button>
                <% } %>
                <% } %>
                <input type="hidden" name="pwmFormID" id="pwmFormID" value="<pwm:FormID/>"/>
            </div>
        </form>
    </div>
    <script type="text/javascript">
    </script>
</div>
<%@ include file="fragment/footer.jsp" %>
</body>
</html>


