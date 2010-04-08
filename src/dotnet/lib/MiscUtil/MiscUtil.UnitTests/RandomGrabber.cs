using System;
using MiscUtil;

/// <summary>
/// Class just used for testing.
/// </summary>
public class RandomGrabber
{
	int[] numbers;
	int size;

	public RandomGrabber(int size, bool keepNumbers)
	{
		this.size = size;
		if (keepNumbers)
		{
			numbers = new int[size];
		}
	}

	public void GrabNumbers()
	{
		for (int i=0; i < size; i++)
		{
			int number = StaticRandom.Next(100000);
			if (numbers != null)
			{
				numbers[i] = number;
			}
		}
	}

	public override bool Equals(object o)
	{
		RandomGrabber other = (RandomGrabber)o;
		for (int i=0; i < size; i++)
		{
			if (numbers[i] != other.numbers[i])
			{
				return false;
			}
		}
		return true;
	}

	public override int GetHashCode()
	{
		throw new NotImplementedException();
	}
}
