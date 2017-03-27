package com.tunjid.rcswitchcontrol.abstractclasses;

import android.support.v7.widget.RecyclerView;

/**
 * Base {@link android.support.v7.widget.RecyclerView.Adapter}
 * <p>
 * Created by tj.dahunsi on 2/13/17.
 */

public abstract class BaseRecyclerViewAdapter<VH extends BaseViewHolder, T extends BaseRecyclerViewAdapter.AdapterListener>
        extends RecyclerView.Adapter<VH> {

    protected T adapterListener;

    public BaseRecyclerViewAdapter(T adapterListener) {
        this.adapterListener = adapterListener;
    }

    public BaseRecyclerViewAdapter() {
    }


    /**
     * A listener for any iteraction this adapter carries
     */
    public interface AdapterListener {

    }

}
