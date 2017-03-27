package com.tunjid.rcswitchcontrol.abstractclasses;

import android.support.v7.widget.RecyclerView;
import android.view.View;

/**
 * Base {@link android.support.v7.widget.RecyclerView.ViewHolder}
 * <p>
 * Created by tj.dahunsi on 2/13/17.
 */

public abstract class BaseViewHolder<T extends BaseRecyclerViewAdapter.AdapterListener>
        extends RecyclerView.ViewHolder {

    protected T adapterListener;

    public BaseViewHolder(View itemView) {
        super(itemView);
    }

    public BaseViewHolder(View itemView, T adapterListener) {
        super(itemView);
        this.adapterListener = adapterListener;
    }

}
