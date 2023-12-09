package com.example.dedrone

import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import android.os.Bundle
import android.view.View
import android.widget.Toast
import com.google.firebase.auth.FirebaseAuth
import android.widget.EditText


class LoginActivity : AppCompatActivity() {
    private lateinit var  auth: FirebaseAuth

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        title="Login"
        auth= FirebaseAuth.getInstance()
    }

    fun login(view: View){
        val emailEditText: EditText = findViewById(R.id.editTextEmailAddress)
        val email = emailEditText.text.toString()
        val passwordEditText: EditText = findViewById(R.id.editTextPassword)
        val password = passwordEditText.text.toString()

        auth.signInWithEmailAndPassword(email,password).addOnCompleteListener { task ->
            if(task.isSuccessful){
                val intent= Intent(this,MainActivity::class.java)
                startActivity(intent)
                finish()
            }
        }.addOnFailureListener { exception ->
            Toast.makeText(applicationContext,exception.localizedMessage, Toast.LENGTH_LONG).show()
        }
    }

}