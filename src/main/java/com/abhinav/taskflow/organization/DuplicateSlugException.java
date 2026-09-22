package com.abhinav.taskflow.organization;

public class DuplicateSlugException extends RuntimeException
{
    public DuplicateSlugException(String message)
    {
        super(message);
    }
}
