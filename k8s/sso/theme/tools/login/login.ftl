<#--
  Substitui o login.ftl do keycloak.v2 para a tela ter só usuário, senha e dois botões (Entrar e o do
  provedor), sem o botão de mostrar senha. O layout em volta continua o template.ftl do keycloak.v2:
  ao atualizar o Keycloak, compare este arquivo com o login.ftl dele.
-->
<#import "template.ftl" as layout>
<@layout.registrationLayout displayMessage=!messagesPerField.existsError('username','password'); section>
    <#if section = "header">
        ${msg("loginAccountTitle")}
    <#elseif section = "form">
        <p class="tools-lead">${msg("toolsLoginLead")}</p>
        <#if realm.password>
            <#assign hasError = messagesPerField.existsError('username','password')>
            <form id="kc-form-login" class="tools-form" onsubmit="login.disabled = true; return true;" action="${url.loginAction}" method="post" novalidate="novalidate">
                <#if !usernameHidden??>
                    <div class="tools-field">
                        <label for="username"><#if !realm.loginWithEmailAllowed>${msg("username")}<#elseif !realm.registrationEmailAsUsername>${msg("usernameOrEmail")}<#else>${msg("email")}</#if></label>
                        <input id="username" name="username" type="text" value="${login.username!''}" autocomplete="username" autofocus<#if hasError> aria-invalid="true" aria-describedby="input-error"</#if>>
                    </div>
                </#if>
                <div class="tools-field">
                    <label for="password">${msg("password")}</label>
                    <input id="password" name="password" type="password" autocomplete="current-password"<#if usernameHidden??> autofocus</#if><#if hasError> aria-invalid="true" aria-describedby="input-error"</#if>>
                </div>
                <#if hasError>
                    <p id="input-error" class="tools-error" aria-live="polite">${kcSanitize(messagesPerField.getFirstError('username','password'))?no_esc}</p>
                </#if>
                <input type="hidden" id="id-hidden-input" name="credentialId" <#if auth.selectedCredential?has_content>value="${auth.selectedCredential}"</#if>>
                <button id="kc-login" name="login" type="submit" class="tools-button tools-button-primary">${msg("doLogIn")}</button>
            </form>
        </#if>
    <#elseif section = "socialProviders">
        <#if realm.password && social.providers?? && social.providers?has_content>
            <div id="kc-social-providers" class="tools-social">
                <p class="tools-divider">${msg("toolsOr")}</p>
                <#list social.providers as p>
                    <a id="social-${p.alias}" class="tools-button tools-button-secondary" href="${p.loginUrl}">
                        <#if p.alias == "microsoft"><img src="${url.resourcesPath}/img/microsoft.svg" alt="" width="18" height="18"></#if>
                        <span>${msg("toolsSignInWith", p.displayName!)}</span>
                    </a>
                </#list>
            </div>
        </#if>
    </#if>
</@layout.registrationLayout>
