<template>
    <v-app>
        <v-content>
            <v-container fluid fill-height>
                <v-layout align-center justify-center>
                    <v-flex xs12 sm8 md4>
                        <v-tabs color="primary" v-model="selectedTab">
                            <v-tab>Login</v-tab>
                            <v-tab>Register</v-tab>
                            <v-tab-item>
                                <v-card class="elevation-12">
                                    <v-card-text>
                                        <v-form @submit="login">
                                            <v-text-field prepend-icon="home" name="host" type="text" title="Server host"
                                                          v-model="loginForm.host"></v-text-field>
                                            <v-text-field prepend-icon="person" name="login" type="text"
                                                          v-model="loginForm.username"></v-text-field>
                                            <v-text-field prepend-icon="lock" name="password" type="password"
                                                          v-model="loginForm.password"></v-text-field>

                                            <v-card-actions>
                                                <v-spacer></v-spacer>
                                                <v-btn color="primary" type="submit" :disabled="this.formsDisabled">Login</v-btn>
                                            </v-card-actions>
                                        </v-form>
                                    </v-card-text>

                                </v-card>
                            </v-tab-item>
                            <v-tab-item>
                                <v-card class="elevation-12">
                                    <v-card-text>
                                        <v-form @submit="register">
                                            <v-text-field prepend-icon="home" name="host" type="text" title="Server host"
                                                          v-model="registerForm.host"></v-text-field>
                                            <v-text-field prepend-icon="person" name="login" type="text"
                                                          v-model="registerForm.username"></v-text-field>
                                            <v-text-field prepend-icon="lock" name="password" type="password"
                                                          v-model="registerForm.password"></v-text-field>

                                            <v-card-actions>
                                                <v-spacer></v-spacer>
                                                <v-btn color="primary" type="submit" :disabled="this.formsDisabled">Create account</v-btn>
                                            </v-card-actions>
                                        </v-form>
                                    </v-card-text>

                                </v-card>
                            </v-tab-item>
                        </v-tabs>
                    </v-flex>
                </v-layout>
            </v-container>
        </v-content>
    </v-app>
</template>

<script>
    import axios from 'axios';

    export default {
        name: 'LoginForm',
        data() {
            return {
                loginForm: {
                    host: null,
                    username: null,
                    password: null
                },
                registerForm: {
                    host: null,
                    username: null,
                    password: null
                },
                formsDisabled: false,
                selectedTab: 0
            }
        },
        props: {
            ajax: Function,
            asyncActionWithNotification: Function
        },
        methods: {
            login(evt) {
                evt.preventDefault();

                if (this.loginForm.host == null || this.loginForm.host.trim() === "") {
                    this.$snotify.warning("You must enter server host");
                    return
                }
                if (this.loginForm.username == null || this.loginForm.username.trim() === "") {
                    this.$snotify.warning("You must enter username");
                    return
                }
                if (this.loginForm.password == null || this.loginForm.password.trim() === "") {
                    this.$snotify.warning("You must enter password");
                    return
                }

                this.formsDisabled = true;

                this.asyncActionWithNotification("login", {
                        host: this.loginForm.host,
                        username: this.loginForm.username,
                        password: this.loginForm.password
                    }, "Logging in", (resp) => new Promise((success, error) => {
                        this.formsDisabled = false;

                        if (resp.success) {
                            success("Login successful!");
                            this.$emit("login")
                        } else {
                            error("Login unsuccessful")
                        }
                    })
                );

            },
            register(evt) {
                evt.preventDefault();

                if (this.registerForm.host == null || this.registerForm.host.trim() === "") {
                    this.$snotify.warning("You must enter server host");
                    return
                }
                if (this.registerForm.username == null || this.registerForm.username.trim() === "") {
                    this.$snotify.warning("You must enter username");
                    return
                }
                if (this.registerForm.password == null || this.registerForm.password.trim() === "") {
                    this.$snotify.warning("You must enter password");
                    return
                }

                this.formsDisabled = true;

                this.asyncActionWithNotification("register", {
                        host: this.registerForm.host,
                        username: this.registerForm.username,
                        password: this.registerForm.password
                    }, "Creating account", (resp) => new Promise((success, error) => {
                        this.formsDisabled = false;
                        this.selectedTab = 0; // switch to login

                        if (resp.success) {
                            success("Account registered, go ahead and login!");
                            this.loginForm = this.registerForm

                        } else {
                            error("Account NOT registered, because: " + t.reason)
                        }
                    })
                )
            },
        }
    }
</script>

<style scoped lang="scss">

    div#loginFormTabs {
        border: 1px solid black;
        border-radius: 5px;
        display: block;
        padding: 5px;
        width: 500px;
        height: 200px;
        position: absolute;
        left: 50%;
        top: 50%;
        margin: -100px 0 0 -250px;
    }

    .formInput {
        margin: 10px 0 0 0;
    }

    .formButton {
        margin: 10px 0 0 -75px;
        display: block;
        width: 150px;
        position: relative;
        left: 50%;
    }
</style>