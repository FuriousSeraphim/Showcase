package io.seraphim.headsandhands.example

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button

class MainScreen : ArchController<EmptyPresenter> {
    constructor() : super()
    constructor(args: Bundle?) : super(args)

    override fun providePresenter() = EmptyPresenter
    override fun provideLayoutRes() = R.layout.screen_main

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup): View {
        val view = super.onCreateView(inflater, container)

        view.findViewById<Button>(R.id.main_sign_in).setOnClickListener { push(AuthScreen()) }

        return view
    }
}