package io.evotrace.idea.settings;

import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBPasswordField;
import com.intellij.ui.components.JBTextField;
import com.intellij.util.ui.FormBuilder;

import javax.swing.*;

public class EvotraceSettingsPanel {

    private final JPanel panel;
    private final JBTextField serverUrlField = new JBTextField();
    private final JBTextField projectKeyField = new JBTextField();
    private final JBTextField usernameField = new JBTextField();
    private final JBPasswordField passwordField = new JBPasswordField();

    public EvotraceSettingsPanel() {
        panel = FormBuilder.createFormBuilder()
                .addLabeledComponent(new JBLabel("服务端地址:"), serverUrlField, 1, false)
                .addLabeledComponent(new JBLabel("项目标识:"), projectKeyField, 1, false)
                .addLabeledComponent(new JBLabel("用户名:"), usernameField, 1, false)
                .addLabeledComponent(new JBLabel("密码:"), passwordField, 1, false)
                .addComponentFillVertically(new JPanel(), 0)
                .getPanel();
    }

    public JPanel getComponent() {
        return panel;
    }

    public String getServerUrl() {
        return serverUrlField.getText();
    }

    public void setServerUrl(String v) {
        serverUrlField.setText(v);
    }

    public String getProjectKey() {
        return projectKeyField.getText();
    }

    public void setProjectKey(String v) {
        projectKeyField.setText(v);
    }

    public String getUsername() {
        return usernameField.getText();
    }

    public void setUsername(String v) {
        usernameField.setText(v);
    }

    public String getPassword() {
        return new String(passwordField.getPassword());
    }

    public void setPassword(String v) {
        passwordField.setText(v);
    }
}
