<template>
    <v-app>
        <v-content>
            <v-container fluid fill-height>
                <v-layout align-center justify-center>
                    <v-flex xs12 sm8 md4>
                        <v-tabs color="primary">
                            <v-tab :key="login">Login</v-tab>
                            <v-tab :key="register">Register</v-tab>
                            <v-tab-item :key="login">
                                <v-card class="elevation-12">
                                    <v-card-text>
                                        <v-form @submit="doLogin">
                                            <v-text-field prepend-icon="home" name="host" type="text" title="Server host"
                                                          v-model="loginForm.host"></v-text-field>
                                            <v-text-field prepend-icon="person" name="login" type="text"
                                                          v-model="loginForm.username"></v-text-field>
                                            <v-text-field prepend-icon="lock" name="password" type="password"
                                                          v-model="loginForm.password"></v-text-field>

                                            <v-card-actions>
                                                <v-spacer></v-spacer>
                                                <v-btn color="primary" type="submit">Login</v-btn>
                                            </v-card-actions>
                                        </v-form>
                                    </v-card-text>

                                </v-card>
                            </v-tab-item>
                            <v-tab-item :key="register">
                                <v-card class="elevation-12">
                                    <v-card-text>
                                        <v-form>
                                            <v-text-field prepend-icon="home" name="host" type="text" title="Server host"
                                                          v-model="loginForm.host"></v-text-field>
                                            <v-text-field prepend-icon="person" name="login" type="text"
                                                          v-model="loginForm.username"></v-text-field>
                                            <v-text-field prepend-icon="lock" name="password" type="password"
                                                          v-model="loginForm.password"></v-text-field>

                                            <v-card-actions>
                                                <v-spacer></v-spacer>
                                                <v-btn color="primary" type="submit">Create account</v-btn>
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
    const HostUrl = "localhost:9000";

    import axios from 'axios';

    export default {
        name: 'LoginForm',
        data() {
            return {
                loginForm: {
                    host: null,
                    username: null,
                    password: null
                }
            }
        },
        props: {
            ajax: Function,
            asyncActionWithNotification: Function
        },
        methods: {
            doLogin(evt) {
                evt.preventDefault();

                this.asyncActionWithNotification("login", {
                        host: this.loginForm.host,
                        username: this.loginForm.username,
                        password: this.loginForm.password
                    }, "Logging in", (resp) => new Promise((success, error) => {
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

                this.ajax("register", {
                    host: this.loginForm.host,
                    username: this.loginForm.username,
                    password: this.loginForm.password
                }).then(t => {
                    if (t.success) {
                        this.cloudResponseMessage = "Account registered: " + t.account_id
                    } else {
                        this.cloudResponseMessage = "Account NOT registered, because: " + t.reason
                    }
                })
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